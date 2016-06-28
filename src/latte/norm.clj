(ns latte.norm
  (:require [clj-by.example :refer [example do-for-example]])
  (:require [latte.syntax :as stx])
  )

;; (s/exercise ::sx/binder)

;;{
;; # Normalization
;;}

(def ^:private +examples-enabled+)

;;{
;; ## Beta-reduction

;; a tricky example :
;;  ((lambda [z :type] (lambda [x :type] (x y))) x)
;;   ~~> (lambda [x :type] (x x)    is wrong
;;
;;   ((lambda [z :type] (lambda [x :type] (x z))) x)
;; = ((lambda [z :type] (lambda [y :type] (y z))) x)
;;   ~~> (lambda [y :type] (y x)    is correct

(defn redex? [t]
  (and (stx/app? t)
       (stx/lambda? (first t))))

(example
 (redex? '[(λ [x ✳] x) y]) => true)

(defn beta-reduction [t]
  (if (redex? t)
    (let [[[_ [x _] body] rand] t]
      (stx/subst body x rand))
    (throw (ex-info "Cannot beta-reduce. Not a redex" {:term t}))))

(example
 (beta-reduction '[(λ [x ✳] [x x]) y])
 => '[y y])

(defn beta-step [t]
  (cond
    ;; reduction of a redex
    (redex? t) [(beta-reduction t) true]
    ;; binder
    (stx/binder? t)
    (let [[binder [x ty] body] t]
      ;; 1) try reduction in binding type
      (let [[ty' red?] (beta-step ty)]
        (if red?
          [(list binder [x ty'] body) true]
          ;; 2) try reduction in body
          (let [[body' red?] (beta-step body)]
            [(list binder [x ty] body') red?]))))
    ;; application
    (stx/app? t)
    (let [[left right] t
          ;; 1) try left reduction
          [left' red?] (beta-step left)]
      (if red?
        [[left' right] true]
        ;; 2) try right reduction
        (let [[right' red?] (beta-step right)]
          [[left right'] red?])))
    ;; reference
    (stx/ref? t)
    (let [[def-name & args] t
          [args' red?] (reduce (fn [[res red?] arg]
                                 (let [[arg' red?'] (beta-step arg)]
                                   [(conj res arg') (or red? red?')])) [[] false] args)]
      [(list* def-name args') red?])
    ;; other cases
    :else [t false]))

(defn beta-red [t]
  (let [[t' _] (beta-step t)]
    t'))

(example
 (beta-red '[(λ [x ✳] x) y]) => 'y)

(example
 (beta-red '[[(λ [x ✳] x) y] z]) => '[y z])

(example
 (beta-red '(λ [y [(λ [x □] x) ✳]] y))
 => '(λ [y ✳] y))


(example
 (beta-red '[z [(λ [x ✳] x) y]]) => '[z y])

(example
 (beta-red '[x y]) => '[x y])

;;{
;; ## Delta-reduction (unfolding of definitions)
;;}

(defn instantiate-def [params body args]
  (loop [args args, params params, sub {}]
    (if (seq args)
      (if (empty? params)
        (throw (ex-info "Not enough parameters (please report)" {:args args}))
        (recur (rest args) (rest params) (assoc sub (ffirst params) (first args))))
      (loop [params (reverse params), res body]
        (if (seq params)
          (recur (rest params) (list 'λ (first params) res))
          (stx/subst res sub))))))

(example
 (instantiate-def '[[x ✳] [y ✳] [z ✳]]
                  '[[x y] z]
                  '((λ [t ✳] t) t1 [t2 t3]))
 => '[[(λ [t ✳] t) t1] [t2 t3]])

(example
 (instantiate-def '[[x ✳] [y ✳] [z ✳] [t ✳]]
                  '[[x y] [z t]]
                  '((λ [t ✳] t) t1 [t2 t3]))
 => '(λ [t' ✳] [[(λ [t ✳] t) t1] [[t2 t3] t']]))

(example
 (instantiate-def '[[x ✳] [y ✳] [z ✳]]
                  '[[x y] z]
                  '())
 => '(λ [x ✳] (λ [y ✳] (λ [z ✳] [[x y] z]))))

 (defn delta-reduction [def-env t]
  (if (not (stx/ref? t))
    (throw (ex-info "Cannot delta-reduce: not a reference term." {:term t}))
    (let [[name & args] t]
      (if (not (get def-env name))
        (throw (ex-info "No such definition" {:term t :def-name name}))
        (let [sdef (get def-env name)]
          (if (> (count args) (:arity sdef))
            (throw (ex-info "Too many arguments to instantiate definition." {:term t :def-name name :nb-params (count (:arity sdef)) :nb-args (count args)}))
            (case (:tag sdef)
              ;; unfolding a defined term
              :term
              (if (:parsed-term sdef)
                [(instantiate-def (:params sdef) (:parsed-term sdef) args) true]
                (throw (ex-info "Cannot unfold term reference (please report)"
                                {:term t :def sdef})))
              :theorem
              (if (:proof sdef)
                [(instantiate-def (:params sdef) (:term (:proof sdef)) args) true]
                (throw (ex-info "Cannot use theorem with no proof." {:term t :theorem sdef})))
              :axiom
              [t false]
              (throw (ex-info "Incorrect tag for definition." {:term t :tag (:tag sdef) :def sdef})))))))))

(example
 (delta-reduction '{test {:arity 3
                          :tag :term
                          :params [[x ✳] [y □] [z ✳]]
                          :parsed-term [y (λ [t ✳] [x [z t]])]}}
                  '(test [a b] c [t (λ [t] t)]))
 => '[[c (λ [t' ✳] [[a b] [[t (λ [t] t)] t']])] true])

(example
 (delta-reduction '{test {:arity 3
                          :tag :axiom
                          :params [[x ✳] [y □] [z ✳]]}}
                  '(test [a b] c [t (λ [t] t)]))
 => '[(test [a b] c [t (λ [t] t)]) false])

(example
 (delta-reduction '{test {:arity 3
                          :tag :term
                          :params [[x ✳] [y □] [z ✳]]
                          :parsed-term [y (λ [t ✳] [x [z t]])]}}
                  '(test [a b] c))
 => '[(λ [z ✳] [c (λ [t ✳] [[a b] [z t]])]) true])

(defn delta-step [def-env t]
  (cond
    ;; binder
    (stx/binder? t)
    (let [[binder [x ty] body] t]
      ;; 1) try reduction in binding type
      (let [[ty' red?] (delta-step def-env ty)]
        (if red?
          [(list binder [x ty'] body) true]
          ;; 2) try reduction in body
          (let [[body' red?] (delta-step def-env body)]
            [(list binder [x ty] body') red?]))))
    ;; application
    (stx/app? t)
    (let [[left right] t
          ;; 1) try left reduction
          [left' red?] (delta-step def-env left)]
      (if red?
        [[left' right] true]
        ;; 2) try right reduction
        (let [[right' red?] (delta-step def-env right)]
          [[left right'] red?])))
    ;; reference
    (stx/ref? t)
    (let [[def-name & args] t
          [args' red?] (reduce (fn [[res red?] arg]
                                 (let [[arg' red?'] (delta-step def-env arg)]
                                   [(conj res arg') (or red? red?')])) [[] false] args)]
      (if red?
        [(list* def-name args') red?]
        (delta-reduction def-env t)))
    ;; other cases
    :else [t false]))

(example
 (delta-step {} 'x) => '[x false])

(example
 (delta-step '{test {:arity 1
                     :tag :term
                     :params [[x ✳]]
                     :parsed-term [x x]}}
             '[y (test [t t])])
 => '[[y [[t t] [t t]]] true])

;;{
;; ## Normalization (up-to beta/delta)
;;}

(defn beta-normalize [t]
  (let [[t' red?] (beta-step t)]
    (if red?
      (recur t')
      t')))

(defn delta-normalize [def-env t]
  (let [[t' red?] (delta-step def-env t)]
    (if red?
      (recur def-env t')
      t')))

(defn beta-delta-normalize [def-env t]
  (let [[t' red?] (beta-step t)]
    (if red?
      (recur def-env t')
      (let [[t'' red?] (delta-step def-env t)]
        (if red?
          (recur def-env t'')
          t'')))))

(defn normalize
  ([t] (beta-normalize t))
  ([def-env t]
   (if (empty? def-env)
     (beta-normalize t)
     (beta-delta-normalize def-env t))))

(example
 (normalize '(λ [y [(λ [x □] x) ✳]] [(λ [x ✳] x) y]))
 => '(λ [y ✳] y))


(defn beta-eq? [t1 t2]
  (let [t1' (normalize t1)
        t2' (normalize t2)]
    (stx/alpha-eq? t1' t2')))

(example
 (beta-eq? '(λ [z ✳] z)
           '(λ [y [(λ [x □] x) ✳]] [(λ [x ✳] x) y])) => true)

(defn beta-delta-eq? [def-env t1 t2]
  (let [t1' (normalize def-env t1)
        t2' (normalize def-env t2)]
    (stx/alpha-eq? t1' t2')))

