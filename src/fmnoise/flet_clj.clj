(ns fmnoise.flet-clj
  (:require [fmnoise.flow :refer [caught ?err fail-with!]])
  (:import [fmnoise.flow Fail]))

(defmacro ^:no-doc flet*
  [catch-handler bindings & body]
  `(try
     (let ~(loop [bound []
                  tail bindings]
             (if-let [[bind-name expression] (first tail)]
               (recur (into bound `[~bind-name (?err (try ~expression
                                                          (catch Throwable ~'t
                                                            (fail-with! {:data {:caught ~'t}
                                                                         :trace? false})))
                                                     (fn [~'err] (fail-with! {:data {:return ~'err}
                                                                              :trace? false})))])
                      (rest tail))
               bound))
       (try ~@body (catch Throwable ~'t (~catch-handler ~'t))))
     (catch Fail ~'ret
       (let [{:keys [~'caught ~'return]} (ex-data ~'ret)]
         (if ~'caught
           (~catch-handler ~'caught)
           ~'return)))))

(defmacro flet
  "Flow adaptation of Clojure `let`. Wraps evaluation of each binding to `call-with` with default handler (defined with `Catch.caught`). If value returned from binding evaluation is failure, it's returned immediately and all other bindings and body are skipped. Custom exception handler function may be passed as first binding with name `:caught`"
  {:style/indent 1}
  [bindings & body]
  (when-not (even? (count bindings))
    (throw (IllegalArgumentException. "flet requires an even number of forms in binding vector")))
  (let [handler-given? (= (first bindings) :caught)
        catch-handler (if handler-given? (second bindings) #(caught %))
        bindings (if handler-given? (rest (rest bindings)) bindings)]
    `(flet* ~catch-handler ~(partition 2 bindings) ~@body)))