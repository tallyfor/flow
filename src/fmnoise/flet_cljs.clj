(ns fmnoise.flet-cljs)

;; This is the ClojureScript version of flet.
;; The API is slightly different from the Clojure version
;; in that you have to pass in the `catch-handler`
;; (ordinarily `caught` from fmnoise.flow, but you may choose something else),
;; and ?err (also from fmnoise.flow).
;; The reason for this is that since the macro must be compiled by the Clojure compiler,
;; it will not have access to the ClojureScript versions of these two functions.
;; The only way I found to work around this is to pass them in.

(defmacro ^:no-doc flet*
  [catch-handler ?err bindings & body]
  `(try
     (let ~(loop [bound []
                  tail bindings]
             (if-let [[bind-name expression] (first tail)]
               (recur (into bound `[~bind-name (~?err (try ~expression
                                                           (catch :default ~'t
                                                             (throw (ex-info "" {:caught ~'t}))))
                                                 (fn [~'err] (throw (ex-info "" {:return ~'err}))))])
                      (rest tail))
               bound))
       (try ~@body (catch :default ~'t (~catch-handler ~'t))))
     (catch :default ~'ret
       (let [{:keys [~'caught ~'return]} (ex-data ~'ret)]
         (if ~'caught
           (~catch-handler ~'caught)
           ~'return)))))

(defmacro flet
  "Flow adaptation of Clojure `let`. Wraps evaluation of each binding to `call-with` with default handler (defined with `Catch.caught`).
  If value returned from binding evaluation is failure, it's returned immediately and all other bindings and body are skipped.
  Exception handler (fmnoise.flow/caught or custom function) and fmnoise.flow/?err must be passed as first two args."
  {:style/indent 1}
  [catch-handler ?err bindings & body]
  (when-not (even? (count bindings))
    (throw "flet requires an even number of forms in binding vector"))
  `(flet* ~catch-handler ~?err ~(partition 2 bindings) ~@body))

