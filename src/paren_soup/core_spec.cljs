(ns paren-soup.core-spec
  (:require [mistakes-were-made.core-spec]
            [html-soup.core-spec]
            [cross-parinfer.core-spec]
            [paren-soup.console-spec]
            [paren-soup.dom-spec]
            [paren-soup.instarepl-spec]
            [paren-soup.core :as c]
            [clojure.spec :as s :refer [fdef]]
            [cljs.spec.test :refer [instrument]]))

(def elem? #(instance? js/Element %))
(def obj? #(instance? js/Object %))

(fdef c/show-error-message!
  :args (s/cat :parent-elem elem? :event obj?))

(fdef c/hide-error-messages!
  :args (s/cat :parent-elem elem?))

(fdef c/rainbow-delimiters
  :args (s/alt
          :two-args (s/cat :parent elem? :level number?)
          :three-args (s/cat :parent elem? :level number? :m any?))
  :ret map?)

(fdef c/line-numbers
  :args (s/cat :line-count number?)
  :ret string?)

(fdef c/refresh-numbers!
  :args (s/cat :numbers elem? :line-count number?))

(fdef c/refresh-instarepl!
  :args (s/cat :instarepl elem? :content elem? :compiler-fn fn?))

(fdef c/post-refresh-content!
  :args (s/cat :content elem? :events-chan any? :state map?))

(fdef c/refresh-content-element!
  :args (s/cat :cropped-state map?))

(fdef c/refresh-content!
  :args (s/cat :content elem? :state map?))

(fdef c/refresh-console-content!
  :args (s/cat :content elem? :state map? :console-start-num number? :clj? boolean?))

(fdef c/add-parinfer-after-console-start
  :args (s/cat :console-start-num number? :mode-type keyword? :state map?)
  :ret map?)

(fdef c/add-parinfer
  :args (s/cat :enable? boolean? :console-start-num number? :mode-type keyword? :state map?)
  :ret map?)

(fdef c/add-newline
  :args (s/cat :state map?)
  :ret map?)

(fdef c/adjust-indent
  :args (s/cat :enable? boolean? :state map?)
  :ret map?)

(fdef c/init-state
  :args (s/cat :content elem? :crop? boolean? :full-selection? boolean?)
  :ret map?)

(fdef c/update-edit-history!
  :args (s/cat :edit-history any? :state map?)
  :ret map?)

(fdef c/update-highlight!
  :args (s/cat :content elem? :last-elem any?))

(fdef c/key-name?
  :args (s/cat :event obj? :key-name keyword?)
  :ret boolean?)

(fdef c/create-editor
  :args (s/cat :paren-soup elem? :content elem? :events-chan any? :opts map?)
  :ret #(instance? c/Editor %))

(fdef c/prevent-default?
  :args (s/cat :event obj? :opts map?)
  :ret boolean?)

(fdef c/add-event-listeners!
  :args (s/cat :content elem? :events-chan any? :opts map?))

(fdef c/init
  :args (s/cat :paren-soup elem? :opts obj?))

(fdef c/init-all
  :args (s/cat))

