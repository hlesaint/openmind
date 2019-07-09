(ns openmind.routes
  (:require [clojure.core.async :as async]
            [clojure.pprint]
            [clojure.walk :as walk]
            [openmind.elastic :as es]))


(defn search-req [query]
  (es/search es/index (es/search->elastic query)))

(defn parse-search-response [res]
  (mapv :_source res))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; routing table
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmulti dispatch (fn [e] (first (:event e))))

(defmethod dispatch :chsk/ws-ping
  [_])

(defmethod dispatch :chsk/uidport-open
  [_])

(defmethod dispatch :default
  [e]
  (println "Unhandled client event:")
  (clojure.pprint/pprint e)
  ;; REVIEW: Dropping unhandled messages is suboptimal.
  nil)

(defmethod dispatch :openmind/search
  [{[_  {:keys [user search]}] :event :keys [send-fn ?reply-fn uid]}]
  (let [nonce (:nonce search)]
    (async/go
      (let [res   (parse-search-response (es/request<! (search-req search)))
            event [:openmind/search-response {:results res :nonce nonce}]]
        (cond
          (fn? ?reply-fn)                    (?reply-fn event)
          (not= :taoensso.sente/nil-uid uid) (send-fn uid event)

          ;; TODO: Logging
          :else (println "No way to return response to sender."))))))

(defn parse-dates [doc]
  (let [formatter (java.text.SimpleDateFormat. "YYYY-MM-dd'T'HH:mm:ss.SSSXXX")]
    (walk/prewalk
     (fn [x] (if (inst? x) (.format formatter x) x))
     doc)))

(defn prepare-doc [doc]
  ;; TODO: Add author info
  ;; TODO: check remaining fields
  (-> doc
      (assoc :text (:extract doc))
      (assoc :created (java.util.Date.))
      (dissoc :extract)
      parse-dates))

(defmethod dispatch :openmind/index
  [{:keys [client-id send-fn ?reply-fn] [_ doc] :event}]
  (async/go
    (let [res (->> doc
                   prepare-doc
                   (es/index-req es/index)
                   es/send-off!
                   async/<!)]
      (when ?reply-fn
        (?reply-fn [:openmind/index-result (:status res)])))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;; Tag Hierarchy
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private top-level-tags
  "Top level tag domains. These are presently invisible to the client since the
  only option is anaesthesia."
  (atom nil))

(def ^:private tags
  "Tag cache (this is going to be looked up a lot)."
  (atom {}))

(defn get-top-level-tags []
  (async/go
    (if @top-level-tags
      @top-level-tags
      (let [t (into {}
                    (map
                     (fn [{id :_id {tag :tag-name} :_source}]
                       [tag id]))
                    (-> (es/top-level-tags es/tag-index)
                        es/request<!))]
        (reset! top-level-tags t)
        t))))

(defn lookup-tags [root]
  (async/go
    (->> (es/subtag-lookup es/tag-index root)
         es/request<!
         (map (fn [{:keys [_id _source]}]
                [_id _source]))
         (into {}))))

(defn get-tag-tree [root]
  (async/go
    (if (contains? @tags root)
      (get @tags root)
      ;; Wasteful, but at least it's consistent
      (let [v (async/<! (lookup-tags root))]
        (swap! tags assoc root v)
        v))))

(defn reconstruct [root re]
  (assoc root :children (into {}
                              (map (fn [c]
                                     (let [t (reconstruct c re)]
                                       [(:id t) t])))
                              (get re (:id root)))))

(defn invert-tag-tree [tree root-node]
  (let [id->node (into {} tree)
        parent->children (->> tree
                              (map (fn [[id x]] (assoc x :id id)))
                              (group-by :parents)
                              (map (fn [[k v]] [(last k) v]))
                              (into {}))]
    (reconstruct root-node parent->children)))

(defmethod dispatch :openmind/tag-tree
  [{:keys [send-fn ?reply-fn] [_ root] :event}]
  (async/go
    (when-let [root-id (get (async/<! (get-top-level-tags)) root)]
      (let [tree    (async/<! (get-tag-tree root-id))
            event   [:openmind/tag-tree (invert-tag-tree
                                         tree
                                         {:tag-name root :id root-id})]]
        (when ?reply-fn
          (?reply-fn event))))))
