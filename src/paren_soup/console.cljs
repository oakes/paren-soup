(ns paren-soup.console
  (:require [mistakes-were-made.core :refer [atom?]]
            [clojure.spec.alpha :as s :refer [fdef]]))

(fdef create-console-history
  :args (s/cat)
  :ret atom?)

(defn create-console-history []
  (atom {:current-line 0
         :lines []
         :start 0}))

(fdef get-console-start
  :args (s/cat :*console-history atom?))

(defn get-console-start [*console-history]
  (-> *console-history deref :start))

(fdef update-console-start!
  :args (s/cat :*console-history atom? :start number?))

(defn update-console-start! [*console-history start]
  (swap! *console-history assoc :start start))

(fdef update-console-history!
  :args (s/cat :*console-history atom? :line string?))

(defn update-console-history!
  "Updates the console history atom."
  [*console-history line]
  (let [{:keys [current-line lines]} @*console-history]
    (swap! *console-history
      (fn [console-history]
        (let [lines (if (and (seq line) (not= line (last lines)))
                      (conj lines line)
                      lines)]
          (assoc console-history
            :current-line (count lines)
            :lines lines))))))

(fdef get-previous-line
  :args (s/cat :*console-history atom?)
  :ret (s/nilable string?))

(defn get-previous-line
  "Returns the previous line from console-history, or nil if there is none."
  [*console-history]
  (let [{:keys [current-line lines]} @*console-history]
    (get lines (dec current-line))))

(fdef get-next-line
  :args (s/cat :*console-history atom?)
  :ret (s/nilable string?))

(defn get-next-line
  "Returns the next line from console-history, or nil if there is none."
  [*console-history]
  (let [{:keys [current-line lines]} @*console-history]
    (get lines (inc current-line))))

(fdef up!
  :args (s/cat :*console-history atom?))

(defn up!
  "Changes the current line and returns the previous line from console-history, or nil if there is none."
  [*console-history]
  (let [line (get-previous-line *console-history)]
    (if line
      (swap! *console-history update :current-line dec)
      (swap! *console-history assoc :current-line -1))
    line))

(fdef down!
  :args (s/cat :*console-history atom?))

(defn down!
  "Changes the current line and returns the next line from console-history, or nil if there is none."
  [*console-history]
  (let [line (get-next-line *console-history)]
    (if line
      (swap! *console-history update :current-line inc)
      (swap! *console-history assoc :current-line (-> @*console-history :lines count)))
    line))

