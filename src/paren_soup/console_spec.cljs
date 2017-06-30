(ns paren-soup.console-spec
  (:require [paren-soup.console :as c]
            [mistakes-were-made.core-spec :refer [atom?]]
            [clojure.spec.alpha :as s :refer [fdef]]))

(fdef c/create-console-history
  :args (s/cat)
  :ret atom?)

(fdef c/get-console-start
  :args (s/cat :console-history atom?))

(fdef c/update-console-start!
  :args (s/cat :console-history atom? :start number?))

(fdef c/update-console-history!
  :args (s/cat :console-history atom? :line string?))

(fdef c/get-previous-line
  :args (s/cat :console-history atom?)
  :ret (s/nilable string?))

(fdef c/get-next-line
  :args (s/cat :console-history atom?)
  :ret (s/nilable string?))

(fdef c/up!
  :args (s/cat :console-history atom?))

(fdef c/down!
  :args (s/cat :console-history atom?))

