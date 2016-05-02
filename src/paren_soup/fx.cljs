(ns paren-soup.fx
  (:require [schema.core :refer [maybe either Any Str Int Keyword Bool]]
            [html-soup.core :as hs]
            [cross-parinfer.core :as cp])
  (:require-macros [schema.core :refer [defn with-fn-validation]]))

; this namespace provides wrappers for resource-intensive functions.
; if we are running in a JavaFX WebView, it will attempt to run the functions
; over the JS->JVM bridge for speed.

(defn map->obj :- js/Object
  [{:keys [text cursor-position indent-type]} :- {Keyword Any}]
  #js {:text text
       :startPos (first cursor-position)
       :endPos (second cursor-position)
       :indentType (some-> indent-type name)})

(defn obj->map :- {Keyword Any}
  [obj :- js/Object]
  {:text (aget obj "text")
   :cursor-position [(aget obj "startPos") (aget obj "endPos")]
   :indent-type (some-> (aget obj "indentType") keyword)})

(defn add-parinfer :- {Keyword Any}
  "Adds parinfer to the state. If window.java exists, it uses the JVM version."
  [mode-type :- Keyword
   state :- {Keyword Any}]
  (if js/window.java
    (obj->map (.addParinfer js/window.java (name mode-type) (map->obj state)))
    (cp/add-parinfer mode-type state)))

(defn add-indent :- {Keyword Any}
  "Adds indent to the state. If window.java exists, it uses the JVM version."
  [state :- {Keyword Any}]
  (if js/window.java
    (obj->map (.addIndent js/window.java (map->obj state)))
    (cp/add-indent state)))

(defn code->html :- Str
  "Converts the given code string to HTML. If window.java exists, it uses the JVM version."
  [text :- Str]
  (if js/window.java
    (.codeToHtml js/window.java text)
    (hs/code->html text)))