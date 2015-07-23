(ns paren-soup.core
  (:require [cljs.tools.reader :refer [read]]
            [cljs.tools.reader.reader-types :refer [indexing-push-back-reader]]
            [schema.core :refer [maybe Any Str Keyword]])
  (:require-macros [schema.core :refer [defn with-fn-validation]]))

(defn read-safe :- (maybe {Keyword Any})
  "Returns a map containing either a form or an exception, along with the line
and column numbers, or nil if EOF is reached."
  [reader :- js/Object]
  (try
    (when-let [form (read reader false nil)]
      (assoc (meta form) :form form))
    (catch js/Object e
      (assoc (.-data e) :message (.-message e)))))

(defn read-all :- [{Keyword Any}]
  "Returns a list of maps representing each top-level form."
  [s :- Str]
  (let [reader (indexing-push-back-reader s)]
    (take-while some? (repeatedly (partial read-safe reader)))))

(defn init! []
  (let [editor (.querySelector js/document ".paren-soup")]
    (set! (.-spellcheck editor) false)
    (.log js/console (pr-str (read-all (.-value editor))))))

(defn init-with-validation! []
  (with-fn-validation (init!)))

(init!)
