(ns paren-soup.console-spec
  (:require [paren-soup.console :as c]
            [clojure.spec :as s :refer [fdef]]))

(fdef c/create-console-history
  :args (s/cat)
  :ret any?)

(fdef c/get-console-start
  :args (s/cat :console-history any?))

(fdef c/update-console-start!
  :args (s/cat :console-history any? :start number?))

(fdef c/update-console-history!
  :args (s/cat :console-history any? :line string?))

(fdef c/get-previous-line
  :args (s/cat :console-history any?)
  :ret (s/nilable string?))

(fdef c/get-next-line
  :args (s/cat :console-history any?)
  :ret (s/nilable string?))

(fdef c/up!
  :args (s/cat :console-history any?))

(fdef c/down!
  :args (s/cat :console-history any?))

