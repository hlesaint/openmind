(ns openmind.sources.biorxiv
  (:require [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.string :as string]
            [openmind.sources.common :as common]
            [openmind.url :as url]))

(defn biorxiv-api-url [doi]
  (str "https://api.biorxiv.org/details/biorxiv/" doi))

(defn biorxiv [url]
  (-> url
        url/parse
        :path
        (string/replace-first #"/content/" "")
        (string/split #"v")
        first
        biorxiv-api-url
        common/fetch))

(defn format-source [{:keys []}]
  {})

(defn find-article [url]
  (async/go
    (try
      (let [res (biorxiv url)]
        (-> res
            async/<!
           (json/read-str :key-fn keyword)
           format-source))
      (catch Exception _
        nil))))