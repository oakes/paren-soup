(ns paren-soup.instarepl
  (:require [goog.string :refer [format]]
            [goog.string.format]
            [html-soup.core :as hs]
            [clojure.string :refer [join]]))

(defn elems->locations
  "Returns the location of each elem."
  [elems top-offset]
  (loop [i 0
         locations (transient [])]
    (if-let [elem (get elems i)]
      (let [top (-> elem .-offsetTop (- top-offset))
            height (-> elem .-offsetHeight)]
        (recur (inc i) (conj! locations {:top top :height height})))
      (persistent! locations))))

(defn results->html
  "Returns HTML for the given eval results."
  [results locations]
  (loop [i 0
         evals (transient [])]
    (let [res (get results i)
          {:keys [top height]} (get locations i)]
      (if (and res top height)
        (recur (inc i)
               (conj! evals
                 (format
                   "<div class='%s' style='top: %spx; height: %spx; min-height: %spx'>%s</div>"
                   (if (array? res) "result error" "result")
                   top
                   height
                   height
                   (some-> (if (array? res) (first res) res)
                           hs/escape-html-str))))
        (join (persistent! evals))))))

(defn get-collections
  "Returns collections from the given DOM node."
  [element]
  (vec (for [elem (-> element .-children array-seq)
             :let [classes (.-classList elem)]
             :when (or (.contains classes "collection")
                       (.contains classes "symbol"))]
         elem)))

