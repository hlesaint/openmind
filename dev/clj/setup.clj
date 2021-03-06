(ns setup
  (:require [clojure.core.async :as async]
            [openmind.elastic :as elastic]
            [openmind.indexing :as index]
            [openmind.datastore :as ds]
            [openmind.util :as util]))

;;;;; Elasticsearch setup

(defn init-cluster! []
  (async/go
    ;; These have to happen sequentially.
    (println
     (async/<!
      (elastic/send-off!
       (assoc (elastic/create-index elastic/index) :method :delete))))
    (println
     (async/<! (elastic/send-off! (elastic/create-index elastic/index))))
    (println
     (async/<! (elastic/send-off! (elastic/set-mapping elastic/index))))))

(defn load-es-from-s3! []
  (let [extract-ids (-> elastic/active-es-index
                        ds/get-index
                        :content)
        extracts (map ds/lookup extract-ids)]
    (async/go
      (async/<! (init-cluster!))
      (run! elastic/index-extract! extracts))))

(defn -main [& args]
  (async/<!! (load-es-from-s3!)))

(defn dump-elastic!
  "Dump last 100 extracts from elastic to edn file.
  This isn't the recommended way to save/restore elastic. For that use the s3
  datastore and `load-es-from-s3!`."
  [filename]
  (async/go
    (->> elastic/most-recent
         elastic/send-off!
         async/<!
         elastic/parse-response
         :hits
         :hits
         (mapv :_source)
         (spit filename))))
