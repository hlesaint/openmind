(ns openmind.routes
  (:require [clojure.core.async :as async]
            [clojure.spec.alpha :as s]
            [clojure.walk :as walk]
            [openmind.elastic :as es]
            [openmind.spec.extract :as extract-spec]
            [openmind.tags :as tags]
            [taoensso.timbre :as log]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; routing table
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- respond-with-fallback
  "Tries to respond directly to sender, if that fails, tries to respond to all
  connected devices logged into the account of the sender."
  [{:keys [send-fn ?reply-fn uid]} msg]
  (cond
    ;; REVIEW: If you're logged in on your phone and your laptop, and you
    ;; search on your laptop, should the search on your phone change
    ;; automatically? I don't think so...
    (fn? ?reply-fn) (?reply-fn msg)

    ;; But if it's the only way to return the result to you...
    (not= :taoensso.sente/nil-uid uid) (send-fn uid msg)

    :else (log/warn "No way to return response to sender." uid msg))  )

;;;;; Offline testing stub
;; TODO: Maybe put this in a new namespace
(defmulti offline-dispatch :id)

(defmethod offline-dispatch :openmind/verify-login
  [req]
  (respond-with-fallback req [:openmind/identity {:name "t" :orcid-id "xyzzy"}]))

(defmethod offline-dispatch :default
  [msg]
  #_(println msg))

(defmulti dispatch (fn [{:keys [id] :as e}]
                     ;; Ignore all internal sente messages at present
                     (when-not (= "chsk" (namespace id))
                       id)))

(defmethod dispatch nil
  [e]
  (log/trace "sente message" e))

(defmethod dispatch :default
  [e]
  (log/warn "Unhandled client event:" e))

;;;;; Search

(defn search->elastic [term filters]
  (async/go
    {:sort  {:created-time {:order :desc}}
     :from  0
     :size  20
     :query {:bool (merge {:filter (async/<! (tags/tags-filter-query
                                              ;; FIXME: Hardcoded anaesthesia
                                              "anaesthesia" filters))}
                          (when (seq term)
                            ;; TODO: Better prefix search:
                            ;; https://www.elastic.co/guide/en/elasticsearch/guide/master/_index_time_search_as_you_type.html
                            ;; or
                            ;; https://www.elastic.co/guide/en/elasticsearch/reference/current/search-suggesters-completion.html
                            ;; or
                            ;; https://www.elastic.co/guide/en/elasticsearch/reference/current/analysis-edgengram-tokenizer.html
                            {:must {:match_phrase_prefix {:text term}}}))}}))

(defn search-req [query]
  (async/go
    (es/search es/index (async/<! query))))

(defn parse-search-response [res]
  (mapv :_source res))

(defn prepare-search [term filters time]
  (search->elastic term filters))

(defmethod dispatch :openmind/search
  [{[_
     {:keys [openmind.search/term
             openmind.search/filters
             openmind.search/time
             :openmind.search/nonce]}]
    :event :as req}]
  (async/go
    (let [res   (-> (prepare-search term filters time)
                    search-req
                    async/<!
                    es/request<!
                    parse-search-response)
          event [:openmind/search-response
                 #:openmind.search{:results res :nonce nonce}]]
      (respond-with-fallback req event))))

(defmethod offline-dispatch :openmind/search
  [{:keys [event] :as req}]
  (println (:event req))
  (respond-with-fallback
   req
   [:openmind/search-response
    #:openmind.search{:results [{:text "asdasd"}]
                      :nonce (:openmind.search/nonce (second event))}]))

;;;;; Login

(defmethod dispatch :openmind/verify-login
  [{:keys [tokens] :as req}]
  (let [res [:openmind/identity (select-keys (:orcid tokens) [:orcid-id :name])]]
    (respond-with-fallback req res)))

;;;;; Create extract

(defn parse-dates [doc]
  (let [formatter (java.text.SimpleDateFormat. "YYYY-MM-dd'T'HH:mm:ss.SSSXXX")]
    (walk/prewalk
     (fn [x] (if (inst? x) (.format formatter x) x))
     doc)))

(defn validate [author doc]
  (cond
    (not= author (:author doc))
    (log/error "Login mismatch, possible attack:" author doc)

    (not (s/valid? ::extract-spec/extract doc))
    (log/warn "Invalid extract received from client:"
              author doc (s/explain-data ::extract-spec/extract doc))

    :else doc))

(defn collapse-maps [doc]
  (let [map-keys [:comments :contrast :confirmed :related :figures]]
    (reduce (fn [doc k]
              (update doc k #(remove empty? (vals %))))
            doc map-keys)))

(defn prepare [doc]
  (-> doc
      ;; Prefer server timestamp over what came from client
      (assoc :created-time (java.util.Date.))
      parse-dates
      collapse-maps))

(defmethod dispatch :openmind/index
  [{:keys [client-id send-fn ?reply-fn uid tokens] [_ doc] :event}]
  (when (not= uid :taoensso.sente/nil-uid)
    (async/go
      (let [res (some->> doc
                         (validate
                          (select-keys (:orcid tokens) [:name :orcid-id]))
                         prepare
                         (es/index-req es/index)
                         es/send-off!
                         async/<!)]
        (if-not (<= 200 (:status res) 299)
          (log/error "Failed to index new extact" res)
          (when ?reply-fn
            (?reply-fn [:openmind/index-result (:status res)])))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Tag Hierarchy
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod dispatch :openmind/tag-tree
  [{:keys [send-fn ?reply-fn] [_ root] :event}]
  (async/go
    (when-let [root-id (get (async/<! (tags/get-top-level-tags)) root)]
      (let [tree    (async/<! (tags/get-tag-tree root-id))
            event   [:openmind/tag-tree (tags/invert-tag-tree
                                         tree
                                         {:tag-name root :id root-id})]]
        (when ?reply-fn
          (?reply-fn event))))))
