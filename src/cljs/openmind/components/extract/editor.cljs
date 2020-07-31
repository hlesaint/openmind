(ns openmind.components.extract.editor
  (:require [cljs.spec.alpha :as s]
            [clojure.set :as set]
            [openmind.components.comment :as comment]
            [openmind.components.common :as common]
            [openmind.components.extract :as extract]
            [openmind.components.extract.editor.figure :as figure]
            [openmind.components.forms :as forms]
            [openmind.components.tags :as tags]
            [openmind.edn :as edn]
            [openmind.events :as events]
            [openmind.spec.extract :as exs]
            [openmind.spec.validation :as validation]
            [openmind.util :as util]
            [re-frame.core :as re-frame]
            [reagent.core :as r]
            [taoensso.timbre :as log]))

(defn validate-source [{:keys [extract/type source] :as extract}]
  (let [spec (if (= type :article)
               ::exs/article-details
               ::exs/labnote-source)
        err (s/explain-data spec (or source {}))]
    (when err
      (log/trace "invalid source\n" err)
      (validation/interpret-explanation err))))

(defn validate-resources [{:keys [resources]}]
  (mapv (fn [r]
          (validation/interpret-explanation
           (s/explain-data ::exs/resource r)))
        resources))

(defn validate-extract
  "Checks form data for extract creation/update against the spec."
  [extract]
  (if (s/valid? ::exs/extract extract)
    {:valid extract}
    (let [err (s/explain-data ::exs/extract extract)
          source-err (validate-source extract)]
      (log/trace "Bad extract\n" err)
      {:errors (assoc (validation/interpret-explanation err)
                      :source source-err
                      :resources (validate-resources extract))})))

(def extract-keys
  [:text
   :author
   :tags
   :source
   :extract/type
   :figure
   :resources
   :history/previous-version])

;; TODO: Pull these out of the specs.
(def article-keys
  [:publication/date
   :url
   :title
   :authors
   :peer-reviewed?
   :doi
   :abstract
   :journal
   :volume
   :issue])

(def labnote-keys
  [:lab
   :investigator
   :institution
   :observation/date])

(defn prepare-extract
  [author {:keys [extract/type] :as extract}]
  (cond-> (select-keys extract extract-keys)

    (empty? (:author extract)) (assoc :author author)

    (= :article type)
    (update-in [:source :authors]
               #(into [] (remove (fn [{:keys [short-name full-name]}]
                                   (and (empty? full-name)
                                        (empty? short-name))))
                      %))

    true (update :resources (fn [r]
                              (into []
                                    (remove #(every? empty? (vals %)))
                                    r)))

    ;; We have to do this in case someone fills in data for both a labnote and
    ;; an article. We don't select-keys, because there may be other stuff not in
    ;; the spec we want to keep around.
    (= :article type) (update :source #(apply dissoc % labnote-keys))
    (= :labnote type) (update :source #(apply dissoc % article-keys))))

(defn sub-new [id]
  (fn [{:keys [entity] :as rel}]
    (if (= entity ::new)
      (assoc rel :entity id)
      rel)))

(defn finalise-extract [prepared {:keys [figure-data relations comments figure]}]
  (let [imm (util/immutable prepared)
        rels (map (sub-new (:hash imm)) relations)
        id (:hash imm)]
    {:imm imm
     :snidbits (concat (when figure [figure-data])
                       (map util/immutable rels)
                       (map (fn [t]
                              (util/immutable
                               {:text t :extract id}))
                            (remove empty? comments)))}))

(re-frame/reg-event-fx
 ::revalidate
 (fn [{:keys [db]} [_ id]]
   (let [extract (prepare-extract
                  (:login-info db)
                  (get-in db [::extracts id :content]))]
     {:dispatch [::form-errors (:errors (validate-extract extract)) id]})))

;;;;; New extract init

(def extract-template
  {:selection []
   :content   {:tags      #{}
               :comments  [""]
               :source    {:authors [{:full-name ""}]}
               :resources [{:label "" :link ""}]
               :relations #{}}
   :errors    nil})

(re-frame/reg-event-fx
 ::new-extract-init
 (fn [{:keys [db]} _]
   (when (empty? (-> db ::extracts ::new :content))
     {:db (assoc-in db [::extracts ::new] extract-template)})))

(re-frame/reg-event-db
 ::clear
 (fn [db [_ id]]
   (-> db
       (update ::extracts dissoc id)
       (dissoc ::similar ::related-search-results))))

(re-frame/reg-event-fx
 ::editing-copy
 (fn [{:keys [db]} [_ id]]
   (let [content (:content (events/table-lookup db id))]
     {:db (update db ::extracts assoc id {:content content})})))

(re-frame/reg-event-db
 ::set-figure-data
 (fn [db [_ fid id]]
   (assoc-in db [::extracts id :content :figure-data]
             (events/table-lookup db fid))))

;;;;; Subs

(re-frame/reg-sub
 ::extracts
 (fn [db]
   (::extracts db)))

(re-frame/reg-sub
 ::extract
 :<- [::extracts]
 (fn [extracts [_ id]]
   (get extracts id)))

(re-frame/reg-sub
 ::content
 (fn [[_ id]]
   (re-frame/subscribe [::extract id]))
 (fn [extract _]
   (:content extract)))

(re-frame/reg-sub
 ::extract-form-errors
 (fn [[_ k]]
   (re-frame/subscribe [::extract k]))
 (fn [extract _]
   (:errors extract)))

(re-frame/reg-sub
 ::form-input-data
 (fn [[_ dk k] _]
   [(re-frame/subscribe [::content dk])
    (re-frame/subscribe [::extract-form-errors dk])])
 (fn [[content errors] [_ dk k]]
   {:content (get content k)
    :errors  (get errors k)}))

(re-frame/reg-sub
 ::nested-form-data
 (fn [[_ dk k] _]
   [(re-frame/subscribe [::content dk])
    (re-frame/subscribe [::extract-form-errors dk])])
 (fn [[content errors] [_ dk k]]
   {:content (get-in content k)
    :errors  (get-in errors k)}))

(re-frame/reg-sub
 ::similar
 (fn [db]
   (::similar db)))

(re-frame/reg-sub
 ::article-extracts
 (fn [db]
   (::article-extracts db)))

(re-frame/reg-sub
 ::related-search-results
 (fn [db]
   (::related-search-results db)))

;; tags

(re-frame/reg-sub
 ::editor-tag-view-selection
 (fn [[_ k]]
   (re-frame/subscribe [::extract k]))
 (fn [extract _]
   (:selection extract)))

(re-frame/reg-sub
 ::editor-selected-tags
 (fn [[_ k]]
   (re-frame/subscribe [::content k]))
 (fn [content _]
   (:tags content)))

(re-frame/reg-event-db
 ::set-editor-selection
 (fn [db [_ id path add?]]
   (assoc-in db [::extracts id :selection]
             (if add?
               path
               (vec (butlast path))))))

(re-frame/reg-event-db
 ::add-editor-tag
 (fn [db [_ id tag]]
   (update-in db [::extracts id :content :tags] conj (:id tag))))

(re-frame/reg-event-db
 ::remove-editor-tag
 (fn [db [_ id & tags]]
   (update-in db [::extracts id :content :tags]
              #(reduce disj % (map :id tags)))))

;;;;; Events

(re-frame/reg-event-db
 ::clear-related-search
 (fn [db]
   (dissoc db ::related-search-results)))


(re-frame/reg-event-db
 ::form-edit
 (fn [db [_ id k v]]
   (let [k (if (vector? k) k [k])]
     (assoc-in db (concat [::extracts id :content] k) v))))

(re-frame/reg-event-db
 ::clear-form-element
 (fn [db [_ id k]]
   (update-in db (concat [::extracts id :content] (butlast k))
              dissoc (last k))))

(re-frame/reg-event-fx
 ::add-figure
 (fn [{:keys [db]} [_ id data-url]]
   (let [author (:login-info db)
         img    (util/immutable {:image-data data-url
                                 :caption ""
                                 :author     author})]
     {:db (-> db
              (assoc-in [::extracts id :content :figure] (:hash img))
              (assoc-in [::extracts id :content :figure-data] img))})))

(re-frame/reg-event-fx
 ::load-figure
 (fn [cofx [_ id file]]
   (let [reader (js/FileReader.)]
     (set! (.-onload reader)
           (fn [e]
             (let [img (->> e
                            .-target
                            .-result)]
               (re-frame/dispatch
                [::add-figure id img]))))
     (.readAsDataURL reader file))))

(re-frame/reg-event-db
 ::form-errors
 (fn [db [_ errors id]]
   (assoc-in db [::extracts id :errors] errors)))

(defn extract-changed? [old new]
  (let [content (dissoc (:content old) :hash :time/created)]
    (when-not (= (:hash old) (:hash (util/immutable new)))
      (util/immutable
       (assoc new :history/previous-version (:hash old))))))

(defn changed? [imm fig extract base]
  (let [rels (:relations @(re-frame/subscribe [:extract-metadata (:hash extract)]))]
    (or (some? imm)
        (some? fig)
        (not= rels (:relations base)))))

(defn update-relations [oldid newid relations]
  (if newid
    (into #{}
          (map (fn [{:keys [entity value] :as rel}]
                 (if (= entity oldid)
                   (assoc rel :entity newid)
                   (assoc rel :value newid))))
          relations)
    relations))

(re-frame/reg-event-fx
 ::update-extract
 (fn [{:keys [db]} [_ id]]
   (let [base                   (get-in db [::extracts id :content])
         extract                (prepare-extract (get db :login-info) base)
         {:keys [valid errors]} (validate-extract extract)]
     (if errors
       {:dispatch [::form-errors errors id]}
       (if (= id ::new)
         (let [{:keys [imm snidbits]} (finalise-extract extract base)]
           {:dispatch [:->server [:openmind/index
                                  {:extract imm :extras snidbits}]]})
         (let [original (events/table-lookup db id)
               fig      (when-not (= (:figure extract) (:figure original))
                          (:figure-data base))
               imm      (extract-changed? original extract)]
           (if (changed? imm fig original base)
             {:dispatch [:->server [:openmind/update
                                    {:new-extract imm
                                     :editor      (get db :login-info)
                                     :previous-id (:hash original)
                                     :figure      fig
                                     :relations   (update-relations
                                                   id (:hash imm)
                                                   (:relations base))}]]}
             ;; no change, just go back to search
             {:dispatch-n [[:notify {:status  :warn
                                     :message "no changes to save"}]
                           [:navigate {:route :search}]]})) )))))

(re-frame/reg-event-fx
 :openmind/index-result
 (fn [_ [_ {:keys [status message]}]]
   (if (= :success status)
     {:dispatch-n [[::clear ::new]
                   [:notify {:status  :success
                             :message
                             (str "new extract successfully submitted\n"
                                  "your search results will reflect the search"
                                  " index once it has been updated")}]
                   [:navigate {:route :search}]]}
     {:dispatch [:notify {:status  :error
                          :message "failed to create extract"}]})))

(re-frame/reg-event-fx
 :openmind/update-response
 (fn [cofx [_ {:keys [status message id]}]]
   (if (= :success status)
     {:dispatch-n [[::clear id]
                   [:notify {:status :success
                             :message
                             (str "changes submitted successfully\n"
                                  "it may take a moment for the changes"
                                  " to be reflected in your results.")}]
                   [:navigate {:route :search}]]}
     {:dispatch [:notify {:status :error :message "failed to save changes"}]})))

(re-frame/reg-event-db
 ::add-relation
 (fn [db [_ id object-id type]]
   (let [author (:login-info db)
         rel    {:attribute type
                 :value     object-id
                 :entity    id
                 :author    author}]
     (if (get-in db [::extracts id :content :relations])
       (update-in db [::extracts id :content :relations] conj rel)
       (assoc-in db [::extracts id :content :relations] #{rel})))))

(re-frame/reg-event-db
 ::remove-relation
 (fn [db [_ id rel]]
   (update-in db [::extracts id :content :relations] disj rel)))

;;;; Components

(defn add-form-data [id {:keys [key] :as elem}]
  (-> elem
      (assoc :data-key id)
      (merge @(re-frame/subscribe [::form-input-data id key]))))

(defn tag-selector
  [{id :data-key}]
  [:div {:style {:min-height "40rem"}}
   [tags/tag-widget {:selection {:read [::editor-tag-view-selection id]
                                 :set  [::set-editor-selection id]}
                     :edit      {:read   [::editor-selected-tags id]
                                 :add    [::add-editor-tag id]
                                 :remove [::remove-editor-tag id]}}]])


(defn source-preview [{:keys [data-key] :as opts}]
  (let [{:keys [source extract/type]}
        @(re-frame/subscribe [::content data-key])]
    (when (and (= type :article) (:abstract source))
      [extract/source-content source])))

(re-frame/reg-event-fx
 ::article-lookup
 (fn [cofx [_ id url]]
   (let [last-searched (-> cofx :db ::extracts (get id) :content :source :url)]
     (when-not (= url last-searched)
       {:dispatch-n
        [[:->server [:openmind/article-lookup {:res-id id :term url}]]
                     [:openmind.components.window/spin]]}))))

(re-frame/reg-event-fx
 :openmind/article-details
 (fn [{:keys [db]} [_ {:keys [res-id term source]}]]
   (let [current (get-in db [::extracts res-id :content :article-search])]
     (if (and (= term current) (seq source))
       {:db         (-> db
                        (update-in [::extracts res-id :content :source]
                                   merge source)
                        (assoc-in [::extracts res-id ::found-article?] true))
        :dispatch-n [[:openmind.components.window/unspin]
                     [:notify {:status :success :message "article found"}]]}
       {:db         (-> db
                        (update-in [::extracts res-id :content] dissoc :source)
                        (update-in [::extracts res-id] dissoc ::found-article?))
        :dispatch-n [[:openmind.components.window/unspin]
                     [:notify {:status :error
                               :message
                               (str "we couldn't find that article\n"
                                    "please enter its details below")}]]}))))

(defn select-button [{:keys [key value content label errors data-key]}]
  [:button.p1.text-white.border-round
   {:class    (if (= content value)
                "bg-dark-blue"
                "bg-blue")
    :on-click #(do (re-frame/dispatch
                    [::form-edit data-key key value])
                   (when errors
                     (re-frame/dispatch [::revalidate data-key])))}
   label])

(defn select-buttons [{:keys [content errors options] :as opts}]
  [:div.flex.flex-column
   (into
    [:div.flex.flex-start
     (when (and errors (not content))
       {:class "form-error border-round border-solid ph"
        :style {:width "max-content"}})]
    (interpose [:div.ml1]
               (map (fn [v] [select-button (merge opts v)])
                    options)))])


(defn source-selector [opts]
  [select-buttons (merge opts {:options [{:value :article
                                          :label "article"}
                                         {:value :labnote
                                          :label "lab note"}]})])

(defn peer-review-widget [opts]
  [select-buttons (merge opts {:options [{:value true
                                          :label "peer reviewed article"}
                                         {:value false
                                          :label "preprint"}]})])

(defn responsive-two-column [l r]
  [:div.vcenter.mb1h.mbr2
   [:div.left-col l]
   [:div.right-col r]])

(defn responsive-three-column [l r f]
  [:div.vcenter.mb1h.mbr2
   [:div.left-col l]
   [:div.right-col
    [:div.middle-col r]
    [:div.feedback-col f]]])

(defn input-row
  [{:keys [label required? full-width? component feedback sublabel] :as field}]
  (let [label-span [:div  [:span [:b label]
                           (when required?
                             [:span.text-red.super.small " *"])]
                    (when sublabel [sublabel field])]]
    (if full-width?
      [:div
       (when label
         [:h4.ctext label-span])
       [component field]]
      (if feedback
        [responsive-three-column
         label-span
         [component field]
         [feedback field]]
        [responsive-two-column
         label-span
         [component field]]))))

(def labnote-details-inputs
  ;; For lab notes we want to get the PI, institution (corp), and date of
  ;; observation.
  [{:component forms/text
    :label "institution"
    :placeholder "university, company, etc."
    :key [:source :institution]
    :required? true}
   {:component forms/text
    :label "lab"
    :placeholder "lab name"
    :key [:source :lab]
    :required? true}
   {:component forms/text
    :label "investigator"
    :placeholder "principle investigator"
    :key [:source :investigator]
    :required? true}
   {:component forms/date
    :label "observation date"
    :required? true
    :key [:source :observation/date]}])

(defn article-search [{:keys [data-key key content] :as opts}]
  (let [waiting? @(re-frame/subscribe [:openmind.components.window/spinner])]
    [:div.flex
     [forms/text opts]
     [:button.bg-blue.ph.mlh.text-white.border-round
      {:style (when waiting? {:cursor :wait})
       :on-click #(re-frame/dispatch [::article-lookup data-key content])}
      "search"]]))

(def source-details-inputs
  ;; For article extracts, we can autofill from pubmed, but if that doesn't
  ;; work, we want the title, author list, publication, and date.
  [{:key         [:article-search]
    :component   article-search
    :label       "find paper"
    :placeholder "article url or DOI"}
   {:component   forms/text
    :label       "link to article"
    :key         [:source :url]
    :placeholder "www.ncbi.nlm.nih.gov/pubmed/..."
    :required?   true}
   {:component peer-review-widget
    :label     "status"
    :title     "is this article peer reviewed, or a preprint?"
    :key       [:source :peer-reviewed?]
    :required? true}
   {:component forms/text
    :label     "doi"
    :key       [:source :doi]
    :required? true}
   {:component forms/textarea
    :label     "title"
    :key       [:source :title]
    :required? true}
   {:component forms/text-input-list
    :label     "authors"
    :key       [:source :authors]
    :sub-key   :full-name
    :required? true}
   {:component forms/date
    :label     "publication date"
    :key       [:source :publication/date]
    :required? true}
   {:component forms/textarea
    :label     "abstract"
    :key       [:source :abstract]}
   {:component forms/text
    :label     "journal"
    :key       [:source :journal]}
   {:component forms/text
    :label     "volume"
    :key       [:source :volume]}
   {:component forms/text
    :label     "issue"
    :key       [:source :issue]}])

(re-frame/reg-sub
 ::found-article?
 (fn [db [_ id]]
   (get-in db [::extracts id ::found-article?])))

(re-frame/reg-event-db
 ::force-edit
 (fn [db [_ id]]
   (assoc-in db [::extracts id ::found-article?] false)))

;; TODO: Abstract this collapsible thing wrapper. There are now three copies of
;; the same logic in this file.
(defn compact-source-preview [opts]
  (let [open? (r/atom false)]
    (fn [{:keys [data-key content]}]
      [:div
       [:div.left-col
        [:a.super.right.plh.prh
         {:on-click (fn [_] (swap! open? not))
          :title (if @open? "collapse" "expand")}
         [:div (when @open? {:style {:transform "rotate(90deg)"}}) "➤"]]
        [:span [:b "article details"]]]
       [:div.right-col
        [:div {:style {:margin-top 0}}
         (if @open?
           [:div
            [:button.right.p1.text-white.border-round.bg-blue
             {:on-click #(re-frame/dispatch [::force-edit data-key])}
             "edit"]
            [extract/source-content content]]
           [extract/citation content])]]])))

(defn source-details [{:keys [data-key content] :as opts}]
  (let [extract @(re-frame/subscribe [::content data-key])
        type    (:extract/type extract)]
    (if (and (= type :article)
             @(re-frame/subscribe [::found-article? data-key]))
      ;; FIXME: This is really kludgy
      [:div.flex.flex-column
       (input-row (merge (first source-details-inputs)
                         {:data-key data-key}
                         @(re-frame/subscribe
                           [::nested-form-data data-key [:article-search]])))
       [compact-source-preview opts]]
      (into [:div.flex.flex-column]
            (comp
             (map (fn [{:keys [key] :as opts}]
                    (merge opts
                           {:data-key data-key}
                           @(re-frame/subscribe
                             [::nested-form-data data-key key]))))
             (map input-row))
            (case type
              :article source-details-inputs
              :labnote labnote-details-inputs
              [])))))

(defn comment-widget [{:keys [data-key] :as opts}]
  (if (= data-key ::new)
    [forms/textarea-list opts]
    (let [comments @(re-frame/subscribe [::edited-comments data-key])]
      [comment/comment-page-content comments])))

(defn relation-button [text event]
  [:button.text-white.ph.border-round.bg-dark-grey
   {:on-click #(re-frame/dispatch event)}
   text])

(defn related-buttons [extract-id]
  (fn  [{:keys [hash] :as extract}]
    (let [ev [::add-relation extract-id hash]]
      (into [:div.flex.space-evenly]
            (map (fn [a]
                   [relation-button (get extract/relation-names a) (conj ev a)]))
            [:related :confirmed :contrast]))))

(defn cancel-button [onclick]
  [:a.border-circle.bg-white.text-black.border-black.relative.right
   {:style    {:cursor   :pointer
               :z-index  105
               :top      "-1px"
               :right    "-1px"}
    :title    "remove relation"
    :on-click (juxt common/halt onclick)}
   [:span.absolute
    {:style {:top   "-2px"
             :right "5px"}}
    "x"]])

(defn relation-summary [{:keys [data-key]}]
  (let [base-rels   @(re-frame/subscribe [:openmind.components.extract/relations])
        extract     @(re-frame/subscribe [::extract data-key])
        new-rels    (:new-relations extract)
        retractions (:retracted-relations extract)
        rel-display (set/difference (set/union base-rels new-rels)
                                    retractions)
        summary     (into {} (map (fn [[k v]] [k (count v)]))
                          (group-by :attribute rel-display))]
    (into [:div.flex.flex-column]
          (map (fn [a]
                 (let [c (get summary a)]
                   (when (< 0 c)
                     [:div {:style {:margin-top "3rem"
                                    :max-width  "12rem"}}
                      [:span
                       {:style {:display :inline-block
                                :width   "70%"}}
                       (get extract/relation-names a)]
                      [:span.p1.border-solid.border-round
                       {:style {:width "20%"}}
                       c]]))))
          [:related :confirmed :contrast])))

(defn relation [data-key {:keys [attribute value entity author] :as rel}]
  (let [other   (if (= data-key entity) value entity)
        extract @(re-frame/subscribe [:content other])
        login   @(re-frame/subscribe [:openmind.subs/login-info])]
    [:span
     (when (= login author)
       [cancel-button #(re-frame/dispatch [::remove-relation data-key rel])])
     [extract/summary extract
      {:controls   (extract/relation-meta attribute)
       :edit-link? false}]]))

(def scrollbox-style
  {:style {:max-height      "40rem"
           :padding         "0.1rem"
           :scrollbar-width :thin
           :overflow-y      :auto
           :overflow-x      :visible}})

(defn related-extracts [{:keys [content data-key]}]
  (into [:div.flex.flex-column scrollbox-style]
        (map (partial relation data-key))
        content))

(defn search-results [key data-key]
  (let [results @(re-frame/subscribe [key])
        selected (into {}
                       (map (fn [{:keys [entity value] :as rel}]
                              [(if (= entity data-key) value entity) rel]))
                       (:relations @(re-frame/subscribe [::content data-key])))]
    (into [:div.flex.flex-column scrollbox-style]
          (into []
                (comp
                 (remove (fn [id] (contains? selected id)))
                 (map (fn [id] @(re-frame/subscribe [:content id])))
                 (map (fn [extract]
                        [extract/summary extract
                         {:controls (related-buttons data-key)
                          :edit-link? false}])))
                results))))

(defn similar-extracts [{:keys [data-key] :as opts}]
  (let [open? (r/atom true)]
    (fn [opts]
      (let [content @(re-frame/subscribe [::content data-key])
            similar @(re-frame/subscribe [::similar])]
        (when (and (< 4 (count (:text content))) (seq similar))
          [:div
           [:div.left-col
            [:a.super.right.plh.prh
             {:on-click (fn [_] (swap! open? not))
              :title (if @open? "collapse" "expand")}
             [:div (when @open? {:style {:transform "rotate(90deg)"}}) "➤"]]
            [:span [:b "possibly similar extracts"]]]
           [:div.right-col
            (if @open?
              [search-results ::similar data-key]
              [:div.pl1 {:style {:padding-bottom "0.3rem"}}
               [:b "..."]])]])))))

(defn shared-source [{:keys [data-key]}]
  (let [open? (r/atom true)]
    (fn [opts]
      (let [content @(re-frame/subscribe [::content data-key])
            same-article @(re-frame/subscribe [::article-extracts])]
        (when (and (= :article (:extract/type content)) (seq same-article))
          [:div
           [:div.left-col
            [:a.super.right.plh.prh
              {:on-click (fn [_] (swap! open? not))}
              [:div (when @open? {:style {:transform "rotate(90deg)"}}) "➤"]]
            [:span [:b "extracts based on this article"]]]
           [:div.right-col
            [:div.border-round.border-solid.ph
             {:style {:border-color :lightgrey
                      :box-shadow "1px 1.5px grey inset"}}
             (if @open?
               [:div.pl1 "placeholder"]
               [:div.pl1
                [:b "not implemented"]])]]])))))

(defn extract-search-results [{:keys [data-key]}]
  [search-results ::related-search-results data-key])

(defn resources-widget [{:keys [data-key content errors key] :as opts}]
  [:div.flex.full-width
   (into [:div.full-width]
         (map-indexed
          (fn [i c]
            (let [err (get errors i)]
              [:div.flex
               [:span.pr1
                {:style {:flex-grow 2}}
                [forms/text {:content  (:label c)
                       :key      [key i :label]
                       :placeholder "type, e.g. data, code, toolkit"
                       :data-key data-key
                       :errors   (:label err)}]]
               [:span
                {:style {:flex-grow 2}}
                [forms/text {:content (:link c)
                       :key [key i :link]
                       :placeholder "link to repository"
                       :data-key data-key
                       :errors (:link err)}]]]))
          content))
   [:a.plh.ptp.bottom-right {:on-click
                     (fn [_]
                       (if (nil? content)
                         (re-frame/dispatch [::form-edit data-key [key] [{}]])
                         (re-frame/dispatch
                          [::form-edit data-key
                           [key (count content)] {}])))}
    "[+]"]])

(def extract-creation-form
  [{:component   forms/textarea
    :label       "extract"
    :rows 4
    :on-change   #(when (< 4 (count %))
                    (re-frame/dispatch
                     [:openmind.components.search/search-request
                      {:term %} ::similar]))
    :key         :text
    :required?   true
    :placeholder "what have you discovered?"}
   {:component   similar-extracts
    :key         :similar
    :full-width? true}
   {:component source-selector
    :label     "source"
    :key       :extract/type
    :required? true}
   {:component   source-details
    :key         :source
    :full-width? true}
   {:component   shared-source
    :key         :same-article
    :full-width? true}
   {:component   figure/figure-select
    :label       "figure"
    :key         :figure
    :placeholder [:span [:b "choose a file"] " or drag it here"]}
   {:component   resources-widget
    :label       "repos"
    :placeholder "link to any code / data that you'd like to share"
    :key         :resources}
   {:component   comment-widget
    :label       "comments"
    :key         :comments
    :placeholder "anything you think is important"}
   {:component   forms/text
    :placeholder "find extract that might be related to this one"
    :on-change   #(re-frame/dispatch
                   (if (< 2 (count %))
                     [:openmind.components.search/search-request
                      {:term %} ::related-search-results]
                     [::clear-related-search]))
    :label       "search for related extracts"
    :key         :search}
   {:component extract-search-results}
   {:component related-extracts
    :label     "related extracts"
    :sublabel  relation-summary
    :key       :relations}
   {:component   tag-selector
    :label       "add filter tags"
    :key         :tags
    :full-width? true}])

(defn extract-editor
  [{{:keys [id] :or {id ::new}} :path-params}]
  (let [id (if (= ::new id) id (edn/read-string id))]
    (into
     [:div.flex.flex-column.flex-start.pl2.pr2
      [:div.flex.space-around
       [:h2 (if (= ::new id)
              "create a new extract"
              "modify extract")]]
      [:div.flex.pb1.space-between.mb2
       [:button.bg-red.border-round.wide.text-white.p1
        {:on-click (fn [_]
                     (re-frame/dispatch [::clear id])
                     (re-frame/dispatch [:navigate {:route :search}]))
         :style {:opacity 0.6}}
        "CANCEL"]

       [:button.bg-dark-grey.border-round.wide.text-white.p1
        {:on-click (fn [_]
                     (re-frame/dispatch [::update-extract id]))}
        (if (= ::new id) "CREATE" "SAVE")]]]
     (map input-row (map (partial add-form-data id) extract-creation-form)))))

(def routes
  [["/new" {:name      :extract/create
            :component extract-editor
            :controllers
            [{:start (fn [_]
                       (re-frame/dispatch [::new-extract-init]))}]}]

   ["/:id/edit" {:name       :extract/edit
                 :component  extract-editor
                 :controllers
                 [{:parameters {:path [:id]}
                   :start (fn [{{id :id} :path}]
                            (re-frame/dispatch [::clear nil])
                            (let [id (edn/read-string id)]
                              (when-not @(re-frame/subscribe [::extract id])
                                (re-frame/dispatch
                                 [:ensure id [::editing-copy id]]))))}]}]])
