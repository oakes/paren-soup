(ns paren-soup.compiler
  (:require [clojure.string :as str]
            [cljs.core.async :refer [chan put! <!]]
            [cljs.js :refer [empty-state eval js-eval]]
            [cljs.reader :refer [read-string]]
            [clojure.walk :refer [walk]])
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:import goog.net.XhrIo))

(defn fix-goog-path [path]
  ; goog/string -> goog/string/string
  ; goog/string/StringBuffer -> goog/string/stringbuffer
  (let [parts (str/split path #"/")
        last-part (last parts)
        new-parts (concat
                    (butlast parts)
                    (if (= last-part (str/lower-case last-part))
                      [last-part last-part]
                      [(str/lower-case last-part)]))]
    (str/join "/" new-parts)))

(defn custom-load!
  ([opts cb]
   (if (re-matches #"^goog/.*" (:path opts))
     (custom-load!
       (update opts :path fix-goog-path)
       [".js"]
       cb)
     (custom-load!
       opts
       (if (:macros opts)
         [".clj" ".cljc"]
         [".cljs" ".cljc" ".js"])
       cb)))
  ([opts extensions cb]
   (if-let [extension (first extensions)]
     (try
       (.send XhrIo
         (str (:path opts) extension)
         (fn [e]
           (if (.isSuccess (.-target e))
             (cb {:lang (if (= extension ".js") :js :clj)
                  :source (.. e -target getResponseText)})
             (custom-load! opts (rest extensions) cb))))
       (catch :default e
         (custom-load! opts (rest extensions) cb)))
     (cb {:lang :clj :source ""}))))

(defn eval-forms [forms cb state current-ns]
  (let [opts {:eval js-eval
              :load custom-load!
              :source-map true
              :context :expr
              :def-emits-var true}
        channel (chan)
        forms (atom forms)
        results (atom [])]
    (go (while (seq @forms)
          (try
            (let [form (first @forms)
                  opts (assoc opts :ns @current-ns)]
              (when (list? form)
                (when (= 'ns (first form))
                  (reset! current-ns (second form))))
              (if (instance? js/Error form)
                (put! channel {:error form})
                (eval state form opts #(put! channel %))))
            (catch js/Error e (put! channel {:error e})))
          (swap! forms rest)
          (swap! results conj (<! channel)))
        (cb (mapv #(or (:error %) (:value %))
                  @results)))))

(defn str->form [s]
  (try
    (read-string s)
    (catch js/Error _)))

(defn wrap-macroexpand [form]
  (if (coll? form)
    (list 'macroexpand (list 'quote form))
    form))

(defn add-timeout-reset [form]
  (list 'do '(ps-reset-timeout!) form))

(defn add-timeout-checks [form]
  (if (and (seq? form) (= 'quote (first form)))
    form
    (let [form (walk add-timeout-checks identity form)]
      (if (and (seq? form) (= 'recur (first form)))
        (list 'do '(ps-check-for-timeout!) form)
        form))))

(defn add-timeouts-if-necessary [forms expanded-forms]
  (for [i (range (count forms))
        :let [expanded-form (get expanded-forms i)]]
    (if (and (coll? expanded-form)
             (contains? (set (flatten expanded-form)) 'recur))
      (add-timeout-reset (add-timeout-checks expanded-form))
      (get forms i))))

(defn form->serializable [form]
  (if (instance? js/Error form)
    (array (or (some-> form .-cause .-message) (.-message form))
      (.-fileName form) (.-lineNumber form))
    (pr-str form)))

(defonce state (empty-state))

(defn read-and-eval-forms [forms cb]
  (let [forms (mapv str->form forms)
        current-ns (atom 'cljs.user)
        eval-cb (fn [results]
                  (cb (map form->serializable results)))
        read-cb (fn [results]
                  (eval-forms (add-timeouts-if-necessary forms results)
                              eval-cb
                              state
                              current-ns))
        init-cb (fn [results]
                  (eval-forms (map wrap-macroexpand forms) read-cb state current-ns))]
    (eval-forms ['(ns cljs.user)
                 '(def ^:private ps-last-time (atom 0))
                 '(defn ^:private ps-reset-timeout! []
                    (reset! ps-last-time (.getTime (js/Date.))))
                 '(defn ^:private ps-check-for-timeout! []
                    (when (> (- (.getTime (js/Date.)) @ps-last-time) 2000)
                      (throw (js/Error. "Execution timed out."))))
                 '(set! *print-err-fn* (fn [_]))]
                init-cb
                state
                current-ns)))

(set! (.-onmessage js/self)
      (fn [e]
        (let [forms (.-data e)]
          (read-and-eval-forms
            forms
            (fn [results]
              (.postMessage js/self (into-array results)))))))
