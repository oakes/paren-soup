(ns paren-soup.core
  (:require [cljs.core.async :refer [chan put! <!]]
            [cljs.tools.reader :refer [read *wrap-value-and-add-metadata?*]]
            [cljs.tools.reader.reader-types :refer [indexing-push-back-reader]]
            [clojure.string :refer [split-lines join replace]]
            [clojure.walk :refer [postwalk]]
            [goog.events :as events]
            [schema.core :refer [maybe either Any Str Int Keyword]])
  (:require-macros [schema.core :refer [defn with-fn-validation]]
                   [cljs.core.async.macros :refer [go]]))

(defn read-safe :- (maybe (either Any js/Error))
  "Returns either a form or an exception object, or nil if EOF is reached."
  [reader :- js/Object]
  (try
    (binding [*wrap-value-and-add-metadata?* true]
      (read reader false nil))
    (catch js/Error e e)))

(defn read-all :- [(either Any js/Error)]
  "Returns a list of values representing each top-level form."
  [s :- Str]
  (let [reader (indexing-push-back-reader s)]
    (take-while some? (repeatedly (partial read-safe reader)))))

(defn tag-list :- [{Keyword Any}]
  "Returns a list of maps describing each tag."
  ([token :- Any]
    (tag-list token []))
  ([token :- Any
    result :- [Any]]
    (flatten
      (conj result
            (if (instance? js/Error token)
              (assoc (.-data token) :message (.-message token) :error? true)
              (let [{:keys [line column end-line end-column wrapped?]} (meta token)
                    value (if wrapped? (first token) token)
                    delimiter-size (if (set? value) 2 1)]
                [; begin tag
                 {:line line :column column :value value}
                 (if (coll? value)
                   [; open delimiter tags
                    {:line line :column column :delimiter? true}
                    {:end-line line :end-column (+ column delimiter-size)}
                    ; child tags
                    (map tag-list value)
                    ; close delimiter tags
                    {:line end-line :column (- end-column 1) :delimiter? true}
                    {:end-line end-line :end-column end-column}]
                   [])
                 ; end tag
                 {:end-line end-line :end-column end-column}]))))))

(defn tag->html :- Str
  "Returns an HTML string for the given tag description."
  [tag :- {Keyword Any}]
  (cond
    (:delimiter? tag) "<span class='delimiter'>"
    (:error? tag) (or (.log js/console (:message tag))
                      "<span class='error'></span>")
    (:line tag) (let [value (:value tag)]
                  (cond
                    (symbol? value) "<span class='symbol'>"
                    (list? value) "<span class='collection list'>"
                    (vector? value) "<span class='collection vector'>"
                    (map? value) "<span class='collection map'>"
                    (set? value) "<span class='collection set'>"
                    (number? value) "<span class='number'>"
                    (string? value) "<span class='string'>"
                    (keyword? value) "<span class='keyword'>"
                    (nil? value) "<span class='nil'>"
                    (or (= value true) (= value false)) "<span class='boolean'>"
                    :else "<span>"))
    (:end-line tag) "</span>"
    :else "<span>"))

(defn add-tags :- Str
  "Returns a copy of the given string with markup added."
  [s :- Str]
  (let [lines (split-lines s)
        tags (tag-list (read-all s))
        tags-by-line (group-by #(or (:line %) (:end-line %)) tags)
        lines (for [line-num (range (count lines))]
                (let [tags-for-line (sort-by #(or (:column %) (:end-column %))
                                             (get tags-by-line (+ line-num 1)))
                      tags-per-column (partition-by #(or (:column %) (:end-column %))
                                                    tags-for-line)
                      html-per-column (map #(join (map tag->html %))
                                           tags-per-column)
                      columns (set (map #(or (:column %) (:end-column %))
                                        tags-for-line))
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
                                   (map join (conj segments current-segment))))]
                  (join (interleave segments (concat html-per-column (repeat ""))))))]
    (join "<br/>" lines)))

(def rainbow-colors ["aqua" "brown" "cornflowerblue"  "fuchsia" "gold"
                     "hotpink" "lime" "orange" "plum" "tomato"])

(defn rainbow-delimiters :- {js/Element Str}
  "Returns a map of elements and colors."
  [parent :- js/Element
   level :- Int]
  (apply merge
         {}
         (for [elem (-> parent .-children array-seq)]
           (cond
             (-> elem .-classList (.contains "delimiter"))
             {elem (get rainbow-colors (mod level (count rainbow-colors)))}
             (-> elem .-classList (.contains "collection"))
             (apply merge {} (rainbow-delimiters elem (inc level)))
             :else
             {}))))

(defn refresh! [editor :- js/Element]
  (set! (.-innerHTML editor) (add-tags (.-innerText editor)))
  (doseq [[elem color] (rainbow-delimiters editor -1)]
    (set! (-> elem .-style .-color) color)))

(defn init! []
  (let [editor (.querySelector js/document ".paren-soup")
        changes (chan)]
    (set! (.-spellcheck editor) false)
    (set! (.-contentEditable editor) true)
    (refresh! editor)
    (events/listen editor "keydown" #(put! changes %))
    (go (while true
          (let [event (<! changes)
                editor (.-target event)]
            (refresh! editor))))))

(defn init-with-validation! []
  (with-fn-validation (init!)))

#_(init!)
