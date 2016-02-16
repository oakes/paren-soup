(ns paren-soup.compiler
  (:require [cljs.core.async :refer [chan put! <!]]
            [cljs.js :refer [empty-state eval js-eval]]
            [cljs.reader :refer [read-string]]
            [clojure.walk :refer [walk]])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(defn eval-forms
  [forms cb state]
  (let [opts {:eval js-eval
              :source-map true
              :context :expr}
        channel (chan)
        forms (atom forms)
        results (atom [])]
    (go (while (seq @forms)
          (try
            (let [form (first @forms)]
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
    (array (.-message form) (.-fileName form) (.-lineNumber form))
    (pr-str form)))

(defn read-and-eval-forms [forms cb]
  (let [forms (mapv str->form forms)
        state (empty-state)
        eval-cb (fn [results]
                  (cb (map form->serializable results)))
        read-cb (fn [results]
                  (eval-forms (add-timeouts-if-necessary forms results)
                              eval-cb
                              state))
        init-cb (fn [results]
                  (eval-forms (map wrap-macroexpand forms) read-cb state))]
    (eval-forms ['(ns cljs.user)
                 '(def ^:private ps-last-time (atom 0))
                 '(defn ^:private ps-reset-timeout! []
                    (reset! ps-last-time (.getTime (js/Date.))))
                 '(defn ^:private ps-check-for-timeout! []
                    (when (> (- (.getTime (js/Date.)) @ps-last-time) 2000)
                      (throw (js/Error. "Execution timed out."))))]
                init-cb
                state)))

(set! (.-onmessage js/self)
      (fn [e]
        (let [[counter forms] (.-data e)]
          (read-and-eval-forms
            forms
            (fn [results]
              (.postMessage js/self (array counter (into-array results))))))))
