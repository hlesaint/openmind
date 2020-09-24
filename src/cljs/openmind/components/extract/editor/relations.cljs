(ns openmind.components.extract.editor.relations
  (:require [re-frame.core :as re-frame]
            [openmind.config :as config]
            [openmind.components.common :as common]
            [openmind.components.extract :as extract]))

(def extracts
  :openmind.components.extract.editor/extracts)

(re-frame/reg-event-db
 ::add-relation
 (fn [db [_ id object-id type]]
   (let [rel {:attribute type
              :value     object-id
              :entity    id}]
     (update-in db [extracts id ::new-relations]
                (fn [rels] (conj (or rels #{}) rel))))))

(re-frame/reg-event-db
 ::remove-relation
 (fn [db [_ id rel]]
   (let [added (get-in db [extracts id ::new-relations])]
     (if (contains? added rel)
       (update-in db [extracts id ::new-relations] disj rel)
       (update-in db [extracts id ::retracted-relations]
                  (fn [rels] (conj (or rels #{}) rel)))))))
(re-frame/reg-sub
 ::saved-relations
 (fn [[_ id]]
   (re-frame/subscribe [:extract-metadata id]))
 (fn [metadata]
   (:relations metadata)))

(re-frame/reg-sub
 ::relation-modifications
 (fn [db [_ id]]
   {:additions   (get-in db [extracts id ::new-relations])
    :retractions (get-in db [extracts id ::retracted-relations])}))

(re-frame/reg-sub
 ::active-relations
 (fn [[_ id]]
   [(re-frame/subscribe [:openmind.subs/login-info])
    (re-frame/subscribe [::saved-relations id])
    (re-frame/subscribe [::relation-modifications id])])
 (fn [[login existing {:keys [additions retractions]}]]
   (into #{}
         (remove (fn [rel] (contains? retractions rel)))
         (concat existing
                 (map (fn [rel] (assoc rel :author login)) additions)))))

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
  (let [relations @(re-frame/subscribe [::active-relations data-key])
        summary   (into {} (map (fn [[k v]] [k (count v)]))
                        (group-by :attribute relations))]
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
     (when (or config/debug? (= login author))
       [cancel-button #(re-frame/dispatch [::remove-relation data-key rel])])
     [extract/summary extract
      {:controls   (extract/relation-meta attribute)
       :edit-link? false}]]))

(defn related-extracts [{:keys [content data-key]}]
  (let [relations @(re-frame/subscribe [::active-relations data-key])]
    (into [:div.flex.flex-column common/scrollbox-style]
          (map (partial relation data-key))
          relations)))