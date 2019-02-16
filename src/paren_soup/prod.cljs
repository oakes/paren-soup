(ns paren-soup.prod
  (:require [paren-soup.core]
            [paren-soup.instarepl :as ir]))

(ir/use-web-worker!)
