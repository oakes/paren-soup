(ns paren-soup.core
  (:require [cljs.tools.reader :refer [read *wrap-value-and-add-metadata?*]]
            [cljs.tools.reader.reader-types :refer [indexing-push-back-reader]]
            [clojure.string :refer [split-lines join replace]]
            [clojure.walk :refer [postwalk]]
            [schema.core :refer [maybe either Any Str Int Keyword]])
  (:require-macros [schema.core :refer [defn with-fn-validation]]))

(def read-result (either [Any] {Any Any} js/Error))

(defn read-safe :- (maybe read-result)
  "Returns either a form or an exception object, or nil if EOF is reached."
  [reader :- js/Object]
  (try
    (binding [*wrap-value-and-add-metadata?* true]
      (read reader false nil))
    (catch js/Error e e)))

(defn read-all :- [read-result]
  "Returns a list of values representing each top-level form."
  [s :- Str]
  (let [reader (indexing-push-back-reader s)]
    (take-while some? (repeatedly (partial read-safe reader)))))

(defn token-list :- [{Keyword Any}]
  "Returns a list of maps describing each token."
  ([token :- Any]
    (token-list token []))
  ([token :- Any
    result :- [Any]]
    (flatten
      (conj result
            (if (instance? js/Error token)
              (assoc (.-data token) :message (.-message token))
              (if (coll? token)
                [(assoc (meta token) :value (if (:wrapped? (meta token))
                                              (first token)
                                              token))
                 (map token-list token)]
                (assoc (meta token) :value token)))))))

(defn tag-list :- [{Keyword Any}]
  "Returns a list of maps describing each HTML tag to be added."
  [tokens :- [{Keyword Any}]]
  (flatten
    (for [token tokens]
      [(select-keys token [:line :column :value])
       (select-keys token [:end-line :end-column])])))

(defn tag->html :- Str
  "Returns an HTML string for the given tag description."
  [tag :- {Keyword Any}]
  (cond
    (-> tag :value symbol?) "<span class='symbol'>"
    (-> tag :value list?) "<span class='list'>"
    (-> tag :value vector?) "<span class='vector'>"
    (-> tag :value map?) "<span class='map'>"
    (-> tag :value set?) "<span class='set'>"
    (-> tag :value number?) "<span class='number'>"
    (-> tag :value string?) "<span class='string'>"
    (-> tag :value keyword?) "<span class='keyword'>"
    (:end-line tag) "</span>"
    :else "<span>"))

(defn add-tags :- Str
  "Returns a copy of the given string with markup added."
  [s :- Str]
  (let [lines (split-lines s)
        tokens (token-list (read-all s))
        tags (tag-list tokens)
        begin-tags-by-line (group-by :line tags)
        end-tags-by-line (group-by :end-line tags)
        lines (for [line-num (range (count lines))]
                (let [begin-tags (get begin-tags-by-line (+ line-num 1))
                      end-tags (get end-tags-by-line (+ line-num 1))
                      get-col #(or (:column %) (:end-column %))
                      tags (sort-by get-col (concat begin-tags end-tags))
                      html (map tag->html tags)
                      columns (set (map get-col tags))
                      line (get lines line-num)
                      segments (loop [i 0
                                      segments []
                                      current-segment []]
                                 (if-let [c (get line i)]
                                   (if (contains? columns (inc i))
                                     (recur (inc i)
                                            (conj segments current-segment)
                                            [c])
                                     (recur (inc i)
                                            segments
                                            (conj current-segment c)))
                                   (conj segments current-segment)))
                      segments (map join segments)
                      segments (map #(replace % " " "&nbsp;") segments)
                      line (join (interleave segments (concat html (repeat ""))))]
                  (str line "<br/>")))]
    (join lines)))

(defn init! []
  (let [test-content (.querySelector js/document "textarea")
        editor (.querySelector js/document ".paren-soup")]
    (set! (.-spellcheck editor) false)
    (set! (.-innerHTML editor) (add-tags (.-value test-content)))))

(defn init-with-validation! []
  (with-fn-validation (init!)))

#_(init!)
