(ns latte.script
  "Declarative proof scripts."

  (:require [clojure.set :as set])
  
  (:require [clj-by.example :refer [example do-for-example]])

  (:require [latte.utils :as u])
  (:require [latte.presyntax :as stx])
  (:require [latte.syntax :refer [free-vars]])
  (:require [latte.typing :as ty :refer [type-of-term]])
  (:require [latte.norm :as norm])
  (:require [latte.core :as core])

  )

(def ^:private +examples-enabled+)

(defn parse-assume-step [script]
  (cond
    (not (= (count script) 3))
    [:ko {:msg "Wrong assume step: 2 arguments needed" :nb-args (dec (count script))}]
    (not (and (vector? (second script))
              (>= (count (second script)) 2)
              (even? (count (second script)))))
    [:ko {:msg "Wrong assume step: bindings must be a non-empty vector of `var type` pairs" :bindings (second script)}]
    :else
    (let [[x ty & rst] (second script)]
        (if (seq rst)
          [:ok {:binding [x ty] :body (list 'assume (vec rst) (nth script 2))}]
          [:ok {:binding [x ty] :body (nth script 2)}]))))

(example
 (parse-assume-step '(assume [x :type] body))
 => '[:ok {:binding [x :type], :body body}])

(example
 (parse-assume-step '(assume [A :type x A] body))
 => '[:ok {:binding [A :type], :body (assume [x A] body)}])

(defn do-assume-step [def-env ctx x ty]
  (if (or (not (symbol? x)) (stx/reserved-symbol? x))
    [:ko {:msg "Not a correct variable in assume step." :provided x}]
    (let [[status ty] (stx/parse-term def-env ty)]
      (if (= status :ko)
        [:ko {:msg "Parse error in assume step" :error ty}]
        (if-not (ty/proper-type? def-env ctx ty)
          [:ko {:msg "Not a proper type in assume step" :term ty}]
          [:ok [def-env (u/vcons [x ty] ctx)]])))))  ;; XXX: ctx should be a seq ?

(example
 (do-assume-step '{test :nothing}
            '[[A ✳]]
            'x 'A)
 => '[:ok [{test :nothing} [[x A] [A ✳]]]])

(example
 (do-assume-step '{test :nothing}
            '[[x A] [A ✳]]
            'y 'x)
 => '[:ko {:msg "Not a proper type in assume step", :term x}])

(defn undo-assume-step [def-env ctx x]
  (if (not= (ffirst ctx) x)
    [:ko {:msg "Cannot undo assume: variable not in head of context." :variable x}]
    [:ok [def-env (rest ctx)]]))

(example
 (undo-assume-step '{test :nothing}
              '[[x A] [A ✳]]
              'x)
 => '[:ok [{test :nothing} ([A ✳])]])

(example
 (undo-assume-step '{test :nothing}
              '[[A ✳] [x A]]
              'x)
 => '[:ko {:msg "Cannot undo assume: variable not in head of context.",
           :variable x}])


;; have-step syntax :
;;
;; (have [step1 params1 ty1 method1 arg1
;;        step2 params2 ty2 method2 arg2]
;;   body)

(defn parse-have-step [script]
  (cond
    (not (= (count script) 3))
    [:ko {:msg "Wrong have step: 2 arguments needed" :nb-args (dec (count script))}]
    (not (and (>= (count script) 5)
              (u/vectorn? (second script) 5)))
    [:ko {:msg "Wrong have step: bindings must be a vector of 5-tuples" :bindings (second script)}]
    :else
    (let [[have-name params ty method have-arg & rst] (second script)
          body (nth script 2)]
      (if (seq rst)
        [:ok {:have-name have-name :params params :have-type ty
              :method method :have-arg have-arg :body (list 'have (vec rst) body)}]
        [:ok {:have-name have-name :params params :have-type ty
              :method method :have-arg have-arg :body body}]))))

(defn do-have-step [def-env ctx name params have-type method have-arg]
  (let [[status term] (case method
                        (:by :term) (stx/parse-term def-env have-arg)
                        (:from :abst :abstr)
                        (if-not (and (vector? have-arg)
                                     (= (count have-arg) 2))
                          [:ko {:msg "Cannot perform have step: argument is not of the form [var term]"
                                :have-arg have-arg}]
                          (if-let [ty (ty/env-fetch ctx (first have-arg))]
                            (stx/parse-term def-env (list 'lambda [(first have-arg) ty] (second have-arg)))
                            [:ko {:msg "No such variable in context" :variable (first have-arg)}]))
                        (throw (ex-info "No such method for proof script." {:have-name name :method method})))]
    (if (= status :ko)
      [:ko {:msg "Cannot perform have step: incorrect term." :have-name name :from term}]
      (let [[status have-type] (stx/parse-term def-env have-type)]
        (if (= status :ko)
          [:ko {:msg "Cannot perform have step: incorrect type." :habe-name name :from have-type}]
          (if-not (ty/type-check? def-env ctx term have-type)
            [:ko {:msg "Cannot perform have step: synthetized term and type do not match."
                  :have-name name
                  :term term :type have-type}]
            (if (nil? name)
              [:ok [def-env ctx]]
              (let [[status tdef] (core/handle-term-definition
                                   {:tag ::core/defterm :name name :doc "<have step>"}
                                   def-env
                                   ctx
                                   params
                                   term)]
                (if (= status :ko)
                  [:ko {:msg "Cannot perform have step: wrong local definition."
                        :have-name name
                        :from tdef}]
                  [:ok [(assoc def-env name tdef) ctx]])))))))))

(example
 (do-have-step {}
          '[[A ✳] [x A]]
          'step [] 'A :by 'x)
 => '[:ok [{step {:tag :latte.core/defterm,
                  :name step, :doc "<have step>", :params [], :arity 0,
                  :type A, :parsed-term x}}
           [[A ✳] [x A]]]])

(defn undo-have-step [def-env ctx name]
  (if-not (contains? def-env name)
    [:ko {:msg "Cannot undo have step: unknown local definition."
          :have-name name}]
    [:ok (dissoc def-env name) ctx]))



(defn do-qed-step [start-def-env end-def-env start-ctx end-ctx term]
  (let [[status term] (stx/parse-term end-def-env term)]
    (if (= status :ko)
      [:ko {:msg "Cannot do QED step: parse error." :error term}]
      (let [delta-env (select-keys end-def-env (set/difference (keys end-def-env)
                                                                (keys start-def-env)))
            term (norm/delta-normalize delta-env term)
            fv (free-vars term)
            count-start-ctx (count start-ctx)]
        (loop [delta-ctx end-ctx, count-delta-ctx (count end-ctx), term term]
          (if (> count-delta-ctx count-start-ctx)
            (let [[x ty] (first delta-ctx)]
              (if (contains? fv x)
                (recur (rest delta-ctx) (dec count-delta-ctx) (list 'lambda [x ty] term))
                (recur (rest delta-ctx) (dec count-delta-ctx) term)))
            [:ok term]))))))

(defn evaluate-script [script start-def-env start-ctx def-env ctx cont-stack]
  (if (seq script)
    (case (first script)
      assume
      (let [[status info] (parse-assume-step script)]
        (if (= status :ko)
          [:ko info]
          (let [{[x ty] :binding body :body} info]
            (let [[status res] (do-assume-step def-env ctx x ty)]
              (if (= status :ko)
                [:ko res]
                (recur body start-def-env start-ctx
                       (first res) (second res) (conj cont-stack (list 'undo-assume-step x))))))))
      undo-assume-step
      (let [[status res] (undo-assume-step def-env ctx (second script))]
        (if (= status :ko)
          [:ko res]
          (recur '() start-def-env start-ctx (first res) (second res) cont-stack)))
      have
      (let [[status info] (parse-have-step script)]
        (if (= status :ko)
          [:ko info]
          (let [{have-name :have-name params :params
                 have-type :have-type method :method have-arg :have-arg
                 body :body} info]
            (let [[status res] (do-have-step def-env ctx have-name params have-type method have-arg)]
              (if (= status :ko)
                [:ko res]
                (recur body start-def-env start-ctx (first res) (second res)
                       (conj cont-stack (list 'undo-have-step have-name))))))))
      undo-have-step
      (let [[status res] (undo-have-step def-env ctx (second script))]
        (if (= status :ko)
          [:ko res]
          (recur '() start-def-env start-ctx (first res) (second res) cont-stack)))
      qed
      (do-qed-step start-def-env def-env start-ctx ctx (second script))
      ;; else
      (throw (ex-info "Cannot evaluate script" {:script script})))
    ;; at end of script
    (if (seq cont-stack)
      (recur (first cont-stack) start-def-env start-ctx def-env ctx (rest cont-stack))
      [:ko {:msg "Missing `qed` step in proof."}])))

