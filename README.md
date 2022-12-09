# flow [![CircleCI](https://circleci.com/gh/fmnoise/flow/tree/master.svg?style=svg)](https://circleci.com/gh/fmnoise/flow/tree/master) [![cljdoc badge](https://cljdoc.org/badge/fmnoise/flow)](https://cljdoc.org/d/fmnoise/flow/CURRENT)

## Usage

Leiningen
```clojure
[fmnoise/flow "4.1.0"]
```
deps.edn
```clojure
fmnoise/flow {:mvn/version "4.1.0"}
```

### Motivation

Consider a trivial example:
```clojure
(defn update-handler [req db]
  (if-let [user (:user req)]
    (if-let [id (:id req)]
      (if-let [entity (fetch-entity db id)]
        (if (accessible? entity user)
          (update-entity! entity (:params req))
          {:error "Access denied" :code 403})
        {:error "Entity not found" :code 404})
      {:error "Missing entity id" :code 400})
    {:error "Login required" :code 401}))
```
Looks ugly enough? Let's add some readability. First, require `flow`:
```clojure
(require '[fmnoise.flow :as flow :refer [then else]])
```
Then let's extract each check to a function to make the code more clear and testable (notice we are using `ex-info` as an error container with the ability to store a map with some data in addition to the message):
```clojure
(defn check-user [req]
  (or (:user req)
    (ex-info "Login requred" {:code 401})))

(defn check-entity-id [req]
  (or (:id req)
    (ex-info "Missing entity id" {:code 400})))

(defn check-entity-exists [db id]
  (or (fetch-entity db id)
    (ex-info "Entity not found" {:code 404})))

(defn check-entity-access [entity user]
  (if (accessible? entity user)
    entity
    (ex-info "Access denied" {:code 403})))
```
Then let's add an error formatting helper to turn `ex-info` data into the desired format:
```clojure
(defn format-error [^Throwable err]
  (assoc (ex-data err)
         :error (.getMessage err))) ;; ex-message in clojure 1.10 can be used instead
```
And finally we can write a pretty readable pipeline (notice thread-last macro usage):
```clojure
(defn update-handler [req db]
  (->> (check-user req)
       (then (fn [_] (check-entity-id req))
       (then #(check-entity-exists db %))
       (then #(check-entity-access % (:user req))
       (then #(update-entity! % (:params req))))
       (else format-error)))
```

### Basic blocks

Let's see what's going on here:

**then** accepts a value and a function. If the value is not an exception instance, it calls the function on it, returning a result. Otherwise it returns the exception instance.

**else** works in the opposite way, simply returning non-exception values and applying the given function to exception instance values. There's also a syntax-sugar version - **else-if**. It accepts an exception class as first agrument, making it pretty useful as a functional `catch` branch replacement:
```clojure
(->> (call / 1 0)
     (then inc) ;; bypassed
     (else-if ArithmeticException (constantly :bad-math))
     (else-if Throwable (constantly :unknown-error))) ;; this is also bypassed cause previous function will return normal value
```

**call** is a functional `try/catch` replacement designed to catch all exceptions (starting from `Throwable` but that can be changed, more details soon) and return their instances so any thrown exception will be caught and passed through the chain. `call` accepts a function and its arguments, wraps the function call in a `try/catch` block and returns either the caught exception instance or the function call result, example:
```clojure
(->> (call / 1 0) (then inc)) ;; => #error {:cause "Divide by zero" :via ...}
(->> (call / 0 1) (then inc)) ;; => 1
```

Using `call` inside `then` may look verbose:
```clojure
(->> (rand-int 10) ;; some calculation which may return 0
     (then (fn [v] (call #(/ 10 v))) ;; can cause "Divide by zero" so should be inside call
```
so there's **then-call** for it (and **else-call** also exists for consistency)
```clojure
(->> (rand-int 10)
     (then-call #(/ 10 %)))
```

If we need to pass both cases (exception instances and normal values) through some function, **thru** is the right tool. It works similarly to `doto` but accepts a function as first argument. It always returns the given value, so the function is called only for side-effects (like error logging or cleaning up):
```clojure
(->> (call / 1 0) (thru println)) ;; => #error {:cause "Divide by zero" :via ...}
(->> (call / 0 1) (thru println)) ;; => 0
```
`thru` may be used similarly to `finally`, despite that it's not exactly the same.

And a small cheatsheet to summarize on basic blocks:

![cheatsheet](https://raw.githubusercontent.com/fmnoise/flow/master/doc/flow.png)


### Early return

Bearing in mind that `call` will catch exceptions and return them immediately, throwing an exception may be used as replacement for `return`:
```clojure
(->> (call get-objects)
     (then-call (partial map
                  (fn [obj]
                    (if (unprocessable? obj)
                      (throw (ex-info "Unprocessable object" {:object obj}))
                      (calculate-result object))))))

```
Another case where early return may be useful is `let`:
```clojure
(defn assign-manager [report-id manager-id]
  (->> (call
         (fn []
           (let [report (or (db-find report-id) (throw (ex-info "Report not found" {:id report-id})))
                 manager (or (db-find manager-id) (throw (ex-info "Manager not found" {:id manager-id})))]
             {:manager manager :report report})))
       (then db-persist))
       (else log-error)))
```
Wrapping a function in `call` and throwing inside `let` in order to achieve early return may look ugly and verbose, so `flow` has its own version of `let` - **flet**, which wraps all evaluations with `call`. If any binding or body evaluation returns an exception instance, it's immediately returned, otherwise it works as a normal `let`:
```clojure
(flet [a 1 b 2] (+ a b)) ;; => 3
(flet [a 1 b (ex-info "oops" {:reason "something went wrong"})] (+ a b)) ;; => #error { :cause "oops" ... }
(flet [a 1 b 2] (Exception. "oops")) ;; => #error { :cause "oops" ... }
(flet [a 1 b (throw (Exception. "boom"))] (+ a b)) ;; => #error { :cause "boom" ... }
(flet [a 1 b 2] (throw (Exception. "boom"))) ;; => #error { :cause "boom" ... }
```
So the previous example can be simplified:
```clojure
(defn assign-manager [report-id manager-id]
  (->> (flet [report (or (db-find report-id) (ex-info "Report not found" {:id report-id}))
              manager (or (db-find manager-id) (ex-info "Manager not found" {:id manager-id}))]
         {:manager manager :report report})
       (then db-persist)
       (else log-error)))
```
**IMPORTANT!** Currently `flet` doesn't provide the possibility to perform any kind of cleanup/finalization in case of early return (think of the `finalize` part of a `try/catch` block) so creating/allocating resources which require manual state management as `flet` bindings is not a good idea. Such cases can be handled by using `flet` with `thru` in the chain and having a closure over bindings for stateful resources (inside `then` in the following example):
```clj
(->> (create-stateful-resource)
     (then (fn [resource]
             (->> (flet [a (some-calculation resource)
                         b (other-calculation resource)]
                    (final-calculation a b))
                  (thru (fn [_] (cleanup-resource resource)))))))
```


### Tuning exception catching

`call` catches `java.lang.Throwable` by default, which may not be what you need, so this behavior can be changed by extending the `Catch` protocol:
```clojure
;; let's say we want to catch everything starting from Exception but throw NullPointerException
(extend-protocol flow/Catch
  Throwable
  (caught [e] (throw e))
  Exception
  (caught [e] e)
  NullPointerException
  (caught [e] (throw e)))

(call + 1 nil) ;; throws NullPointerException
(call #(throw (Exception. "Something went wrong"))) ;; => #error {:cause "Something went wrong" ... }
```
The example above may be used during system startup to perform a global change, but if you need to change behavior in a certain block of code there's **call-with** which works similar to `call` but its first argument is a handler function which is called on caught exception:
```clojure
(defn handler [e]
  (if (instance? clojure.lang.ArityException e) (throw e) e))

(call-with handler inc) ;; throws ArityException, as inc requires more than 1 argument
```

å custom handler may be also passed to `flet` in the first pair of its binding vector:
```clojure
;; this flet works the same as let if an exception occurs
(flet [:caught #(throw %)
       a 1
       b (/ a 0)]
  (+ a b)) ;; throws ArithmeticException

;; but it can do early return if exception is returned as value
(flet [:caught #(throw %)
       a 1
       b (ex-info "Something went wrong" {:because "Monday"})]
  (/ a b)) ;; => #error {:cause "Something went wrong" :data {:because "Monday"} ... }
```

## FAQ

### How is it different from Either?

The core idea of `flow` is clear separation of normal values (everything which is not exception instance) and values which indicate error (exception instance) without involving additional containers. This allows us to get rid of redundant abstractions like `Either`, and also avoids messing with value containers (if you've ever seen `Either.Left` inside `Either.Right` you probably know what I'm talking about). Exceptions are already first-class citizens in the Java world but are usually combined with side-effects (throwing) for propagation purposes, while `flow` actively promotes a more functional usage of it by returning the exception instance:
```clojure
;; construction
(ex-info "User not found" {:id 123})

;; catching and returning instance
(try (/ 1 0) (catch Exception e e))
```
In both examples above we clearly understand that the returned value is an error, so there's no need to wrap it with any other container like `Either` (also, Clojure's core function `ex-info` is the perfect tool for storing additional data in an exception instance and it's already available out of the box). That means no or minimal rework of existing code in order to get started with `flow`, while `Either` would need wrapping both normal and error values into its corresponding `Right` and `Left` containers. Due to described features `flow` is much easier to introduce into existing project than `Either`.

### I have some tooling which returns `Either`, how can I integrate it with `flow`?

`flow` can be configured to treat not only `Throwable` descendants but any custom classes as error values. The core of the `flow` machinery is the `Flow` protocol which defines behavior for separation of errors and normal values. Let's look at an example of some defrecord-based `Either` implementation:

```clojure
(defrecord Left [error])
(defrecord Right [value])

(extend-protocol flow/Flow
  Right
  (?ok [this f] (f (:value this)))
  (?err [this _] this)
  (?throw [this] this)

  Left
  (?ok [this _] this)
  (?err [this f] (f (ex-info "Either.Left" this)))
  (?throw [this] (throw (ex-info "Either.Left" this))))
```

### Isn't using exceptions costly?

In some of examples above an exception instance is constructed and passed through the chain without throwing. That's the main use-case and ideology of `flow` - using exception instance as error value. But we know that constructing an exception is costly due to stacktrace creation. Java 7 has a possibility to omit stacktrace creation, but that change to `ExceptionInfo` was not accepted by the core team (more details [here](https://clojure.atlassian.net/browse/CLJ-2423)) so we ended up creating a custom exception class which implements `IExceptionInfo` but can skip stacktrace creation. It's called `Fail` and there's a handy constuctor for it:
```clojure
(fail-with {:msg "User not found" :data {:id 1}}) ;; => #error {:cause "User not found" :data {:id 1} :via [...] :trace []}

;; it behaves the same as ExceptionInfo
(ex-data *1) ;; => {:id 1}

;; its map may be empty or nil
(fail-with nil) ;; => #error {:cause nil :data {} :via [...] :trace []}

;; stacktrace is disabled by default but can be turned on
(fail-with {:msg "User not found" :data {:id 1} :trace? true})

;; there's also throwing constructor (stacktrace is enabled by default)
(fail-with! {:msg "User not found" :data {:id 1}})
```

## ClojureScript support

Experimental ClojureScript support is added in version 4.0, but it's not battle-tested yet, so feel free to raise an Issue/PR if you face any problems using it.

## Status

As of version 4.0 all the deprecated stuff from earlier versions has been removed and there are no plans to extend library anymore soon, so it can be considered mature. If you're upgrading from earlier versions, see Changelog for list of breaking changes.

## Who’s using Flow?

- [Eventum](https://eventum.no) - connects event organizers with their dream venue
- [Yellowsack](https://yellowsack.com) - dumpster bag & and pick up service

## Acknowledgements

Thanks to Scott Wlaschin for his inspiring talk about Railway Oriented Programming
https://fsharpforfunandprofit.com/rop/

## License

Copyright © 2018 fmnoise

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
