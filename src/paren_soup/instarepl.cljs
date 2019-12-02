(ns paren-soup.instarepl
  (:require [goog.string :refer [format]]
            [goog.string.format]
            [html-soup.core :as hs]
            [clojure.string :refer [join]]
            [paren-soup.dom :refer [text-node?]]
            [clojure.spec.alpha :as s :refer [fdef]]
            [eval-soup.core :as es]))

(def elem? #(instance? js/Element %))

(fdef elems->locations
  :args (s/cat :elems (s/coll-of elem?) :top-offset number?)
  :ret (s/coll-of map?))

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

(fdef truncate
  :args (s/cat :text string? :limit number?)
  :ret string?)

(defn truncate [text limit]
  (if (> (count text) limit)
    (str (subs text 0 limit) "...")
    text))

(fdef results->html
  :args (s/cat :results any? :locations (s/coll-of map?) :limit number?)
  :ret string?)

(defn results->html
  "Returns HTML for the given eval results."
  [results locations limit]
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
                           (truncate limit)
                           hs/escape-html-str))))
        (join (persistent! evals))))))

(fdef get-collections
  :args (s/cat :element elem?)
  :ret (s/coll-of elem?))

(defn get-collections
  "Returns collections from the given DOM node."
  [element]
  (vec (for [elem (-> element .-children array-seq)
             :let [classes (.-classList elem)]
             :when (or (.contains classes "collection")
                       (.contains classes "symbol"))]
         elem)))

(fdef collection->content
  :args (s/cat :elem elem?)
  :ret string?)

(defn collection->content [elem]
  (loop [e elem
         content (.-textContent elem)]
    (if-let [prev (.-previousSibling e)]
      (if (text-node? prev)
        (recur prev (str (.-textContent prev) content))
        content)
      content)))

(def ^:dynamic *web-worker-path* nil)

(fdef use-web-worker!
  :args (s/cat))

(defn use-web-worker! []
  (set! *web-worker-path* "paren-soup-compiler.js"))

(fdef form->serializable
  :args (s/cat :form any?))

(defn form->serializable [form]
  (if (instance? js/Error form)
    (array
      (or (some-> form .-cause .-message) (.-message form))
      (.-fileName form)
      (.-lineNumber form))
    (pr-str form)))

(fdef create-compiler-fn
  :args (s/cat)
  :ret fn?)

(defn create-compiler-fn [disable-timeout?]
  (if-let [worker (some-> *web-worker-path* js/Worker.)]
    (fn [coll receive-fn]
      (set! (.-onmessage worker) #(receive-fn (vec (.-data %))))
      (.postMessage worker (into-array coll)))
    (fn [coll receive-fn]
      (es/code->results
        coll
        (fn [results]
          (receive-fn (into-array (mapv form->serializable results))))
        {:disable-timeout? disable-timeout?}))))

