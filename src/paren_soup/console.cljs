(ns paren-soup.console)

(defn create-console-history []
  (atom {:current-line 0
         :lines []
         :start 0}))

(defn get-console-start [console-history]
  (-> console-history deref :start))

(defn update-console-start! [console-history start]
  (swap! console-history assoc :start start))

(defn update-console-history!
  "Updates the console history atom."
  [console-history line]
  (let [{:keys [current-line lines]} @console-history]
    (swap! console-history
      (fn [console-history-map]
        (let [lines (if (and (seq line) (not= line (last lines)))
                      (conj lines line)
                      lines)]
          (assoc console-history-map
            :current-line (count lines)
            :lines lines))))))

(defn get-previous-line
  "Returns the previous line from console-history, or nil if there is none."
  [console-history]
  (let [{:keys [current-line lines]} @console-history]
    (get lines (dec current-line))))

(defn get-next-line
  "Returns the next line from console-history, or nil if there is none."
  [console-history]
  (let [{:keys [current-line lines]} @console-history]
    (get lines (inc current-line))))

(defn up!
  "Changes the current line and returns the previous line from console-history, or nil if there is none."
  [console-history]
  (let [line (get-previous-line console-history)]
    (if line
      (swap! console-history update :current-line dec)
      (swap! console-history assoc :current-line -1))
    line))

(defn down!
  "Changes the current line and returns the next line from console-history, or nil if there is none."
  [console-history]
  (let [line (get-next-line console-history)]
    (if line
      (swap! console-history update :current-line inc)
      (swap! console-history assoc :current-line (-> @console-history :lines count)))
    line))

