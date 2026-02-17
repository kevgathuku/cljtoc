# Function Specs (fdef) Usage Guide

## Overview

All public tracker protocol functions now have `clojure.spec.alpha/fdef` specifications that define:
- **Arguments**: Types and constraints for function parameters
- **Return values**: Success/error result specifications
- **Invariants**: Relationships between inputs and outputs (`:fn` specs)

## Benefits

1. **Self-documenting**: Specs serve as executable documentation
2. **Runtime validation**: Catch invalid inputs with instrumentation
3. **Generative testing**: Automatically test functions with random valid inputs
4. **Better errors**: Clear, specific error messages when validation fails

## Instrumented Functions

All public functions in `dev.cljtoc.protocol.tracker` namespace:

- `url-encode-binary` - URL-encode binary data per RFC 3986
- `parse-compact-peers-ipv4` - Parse 6-byte IPv4 peer format
- `parse-compact-peers-ipv6` - Parse 18-byte IPv6 peer format
- `parse-dictionary-peers` - Parse legacy dictionary peer format
- `parse-http-tracker-response` - Parse bencode HTTP tracker response
- `build-http-announce-url` - Build HTTP tracker announce request URL

## Example: Required Arguments with Invariants

### build-http-announce-url

**Required fields in request map**:
```clojure
:info-hash   ; 20-byte array
:peer-id     ; 20-byte array
:port        ; Integer 1-65535
:uploaded    ; Non-negative integer
:downloaded  ; Non-negative integer
:left        ; Non-negative integer
```

**Optional fields**:
```clojure
:event       ; :started | :completed | :stopped
:compact     ; Boolean (default true)
:num-want    ; Positive integer
:no-peer-id  ; Boolean
:tracker-id  ; String
```

**Invariant**: If successful, returned URL must start with the provided tracker-url base.

## Using Runtime Validation

Enable instrumentation in REPL or tests:

```clojure
(require '[clojure.spec.test.alpha :as stest])

;; Instrument a specific function
(stest/instrument 'dev.cljtoc.protocol.tracker/build-http-announce-url)

;; Instrument all functions in namespace
(stest/instrument 'dev.cljtoc.protocol.tracker)

;; Test with invalid input - will throw detailed error
(tracker/build-http-announce-url
  "http://tracker.example.com/announce"
  {:port 6881})  ; Missing required fields!

;; Error message shows exactly what's missing:
;; {:port 6881} - failed: (contains? % :info-hash)
;; {:port 6881} - failed: (contains? % :peer-id)
;; {:port 6881} - failed: (contains? % :uploaded)
;; {:port 6881} - failed: (contains? % :downloaded)
;; {:port 6881} - failed: (contains? % :left)
```

## Generative Testing

Use fdefs for property-based testing:

```clojure
(require '[clojure.spec.test.alpha :as stest])

;; Run generative tests (uses fdef to generate valid inputs)
(stest/check 'dev.cljtoc.protocol.tracker/build-http-announce-url
             {:clojure.spec.test.check/opts {:num-tests 100}})

;; Returns test results showing if any generated inputs cause failures
```

Example test (already in tracker_test.clj):

```clojure
(deftest build-http-announce-url-fdef-check-test
  (testing "build-http-announce-url conforms to fdef spec"
    (let [check-result (stest/check 'dev.cljtoc.protocol.tracker/build-http-announce-url
                                     {:clojure.spec.test.check/opts {:num-tests 50}})]
      (is (nil? (-> check-result first :failure))
          "Function should pass all generative tests"))))
```

This test:
1. Generates 50 random valid inputs (matching `::tracker-request` spec)
2. Calls function with each generated input
3. Validates return value matches `:ret` spec
4. Verifies invariants in `:fn` spec hold

## Spec Definitions

### Request Spec (::tracker-request)

```clojure
(s/def ::tracker-request
  (s/keys :req-un [::info-hash ::peer-id ::port
                   ::uploaded ::downloaded ::left]
          :opt-un [::event ::compact ::num-want
                   ::no-peer-id ::tracker-id]))
```

### Result Specs

Success results:
```clojure
{:ok value}  ; where value type depends on function
```

Error results:
```clojure
{:error keyword-type
 :message "Error description"
 :spec-explain {...}}  ; Optional: spec validation details
```

### Common Field Specs

```clojure
::info-hash   ; bytes?, exactly 20 bytes
::peer-id     ; bytes?, exactly 20 bytes
::port        ; int?, range 1-65535
::event       ; #{:started :completed :stopped nil}
::uploaded    ; nat-int? (>= 0)
::downloaded  ; nat-int? (>= 0)
::left        ; nat-int? (>= 0)
::num-want    ; pos-int? (> 0)
::compact     ; boolean?
::no-peer-id  ; boolean?
::tracker-id  ; string?
```

## Invariants Examples

### url-encode-binary

```clojure
:fn #(let [input-len (alength (-> % :args :data))
           output (-> % :ret)]
       ;; Output never longer than input * 3 (each byte -> %XX)
       (<= (count output) (* input-len 3)))
```

### parse-compact-peers-ipv4

```clojure
:fn (s/or
      ;; If successful, peer count = input length / 6
      :success #(let [input-len (alength (-> % :args :peers-bytes))
                      peers (-> % :ret second :ok)]
                  (or (not= :success (first (:ret %)))
                      (= (count peers) (/ input-len 6))))
      :error #(= :error (first (:ret %))))
```

### build-http-announce-url

```clojure
:fn (s/or
      ;; If successful, URL starts with tracker-url
      :success #(let [tracker-url (-> % :args :tracker-url)
                      result-url (-> % :ret second :ok)]
                  (or (not= :success (first (:ret %)))
                      (.startsWith result-url tracker-url)))
      :error #(= :error (first (:ret %))))
```

## Best Practices

1. **Development**: Enable instrumentation during development
   ```clojure
   ;; In dev/user.clj or REPL
   (stest/instrument 'dev.cljtoc.protocol.tracker)
   ```

2. **Testing**: Use fdef-based generative tests to complement example-based tests
   ```clojure
   (stest/check 'your-function {:clojure.spec.test.check/opts {:num-tests 100}})
   ```

3. **Production**: Disable instrumentation in production (it adds overhead)
   ```clojure
   (stest/unstrument)
   ```

4. **Debugging**: When validation fails, examine the explain-data
   ```clojure
   (s/explain ::tracker-request {:port 6881})
   ;; Shows exactly which keys are missing or invalid
   ```

## Performance Considerations

- **Instrumentation overhead**: Adds ~10-30% overhead per function call
- **Generative testing**: Slower than example-based tests (generates random data)
- **Recommendation**:
  - Use instrumentation in development/REPL
  - Use generative tests in CI (run fewer iterations if needed)
  - Disable instrumentation in production builds

## Further Reading

- [Clojure Spec Guide](https://clojure.org/guides/spec)
- [spec.test Documentation](https://clojure.github.io/spec.alpha/clojure.spec.test.alpha-api.html)
- [Generative Testing with Spec](https://clojure.org/guides/spec#_generative_testing)
