(ns paren-soup.core
  (:require [cljs.tools.reader :refer [read]]
            [cljs.tools.reader.reader-types :refer [indexing-push-back-reader]]
            [clojure.walk :refer [postwalk]]
            [schema.core :refer [maybe either Any Str Keyword]])
  (:require-macros [schema.core :refer [defn with-fn-validation]]))

(def read-result (either [Any] js/Error))

(defn read-safe :- (maybe read-result)
  "Returns either a form or an exception object, or nil if EOF is reached."
  [reader :- js/Object]
  (try
    (read reader false nil)
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
                [(assoc (meta token) :type (type token))
                 (map token-list token)]
                (assoc (meta token) :type (type token))))))))

(defn add-markup :- Str
  "Returns a copy of the given string with markup added."
  [s :- Str]
  (let [tokens (read-all s)]
    (pr-str (map token-list tokens))))

(defn init! []
  (let [editor (.querySelector js/document ".paren-soup")]
    (set! (.-spellcheck editor) false)
    (.log js/console (add-markup (.-value editor)))))

(defn init-with-validation! []
  (with-fn-validation (init!)))

#_(init!)
