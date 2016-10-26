(ns paren-soup.instarepl
  (:require [goog.string :refer [format]]
            [goog.string.format]
            [html-soup.core :as hs]
            [clojure.string :refer [join]]
            [paren-soup.dom :refer [text-node?]]))

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

(defn collection->content [elem]
  (loop [e elem
         content (.-textContent elem)]
    (if-let [prev (.-previousSibling e)]
      (if (text-node? prev)
        (recur prev (str (.-textContent prev) content))
        content)
      content)))

(defn create-compiler-fn []
  (try
    (let [eval-worker (js/Worker. "paren-soup-compiler.js")]
      (fn [coll receive-fn]
        (set! (.-onmessage eval-worker) #(receive-fn (vec (.-data %))))
        (.postMessage eval-worker (into-array coll))))
    (catch js/Error _ (fn [_ _] (throw js/Error "Can't compile!")))))

