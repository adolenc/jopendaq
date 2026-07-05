;; Read two linear-domain signals and one explicit-domain signal at the same
;; time, and line all three up on a shared wall-clock time axis.
;;
;;   * The two linear signals are the reference device's analog channels.  Their
;;     domain is an implicit linear rule (tick = start + n*delta), and we read
;;     them together with a MultiReader, which hands back one common domain.
;;   * The explicit-domain signal is the block average of channel 0 produced by
;;     a Statistics function block with DomainSignalType = Explicit: instead of
;;     a linear rule it emits, per average, the actual tick of the first raw
;;     sample in its block.  We read it with a StreamReader.
;;
;; All three ride the same device clock, so every average's explicit tick equals
;; one of the channel ticks -- convert both to timestamps and the columns line
;; up exactly, one average every BLOCK_SIZE channel samples.

(import '(com.opendaq Instance Channel FunctionBlock MultiReader StreamReader Daq)
        '(java.time ZoneOffset)
        '(java.time.format DateTimeFormatter))

(def sample-rate 100.0)   ; Hz, device-wide
(def block-size 10)       ; channel samples per average
(def window 30)           ; channel samples to display
(def chunk-size 5)        ; samples per read
(def fmt (.withZone (DateTimeFormatter/ofPattern "HH:mm:ss.SSSSSS") ZoneOffset/UTC))

(def instance (Instance.))
(def device (.addDevice instance "daqref://device0"))
(.setPropertyValue device "GlobalSampleRate" sample-rate)

(def ch0 (.asType (.findComponent instance "Dev/RefDev0/IO/AI/RefCh0") Channel))
(def ch1 (.asType (.findComponent instance "Dev/RefDev0/IO/AI/RefCh1") Channel))
(.setPropertyValue ch0 "Frequency" 5.0)
(.setPropertyValue ch1 "Frequency" 10.0)
(def ch0-signal (.get (.getSignals ch0) 0))
(def ch1-signal (.get (.getSignals ch1) 0))

;; Statistics block averaging channel 0 in blocks of block-size samples.
;; DomainSignalType = 1 (Explicit) makes its output domain an explicit list of
;; ticks -- one tick per average, taken from the block's first raw sample.
(def stats (.addFunctionBlock instance "RefFBModuleStatistics"))
(.setPropertyValue stats "BlockSize" block-size)
(.setPropertyValue stats "DomainSignalType" 1)
(.connect (.get (.getInputPorts stats) 0) ch0-signal)
(def avg-signal (.get (.getSignals stats) 0))

(def multi-reader (MultiReader. [ch0-signal ch1-signal]))
(def stream-reader (StreamReader. avg-signal))

;; Let both streams warm up so their queues overlap in time.
(Thread/sleep 1000)

;; Fill the display window with many small reads rather than one big one,
;; interleaving the two readers so they advance together.  Channel samples are
;; collected until the window is full; averages go into a tick->value table and
;; keep being read until they have caught up to the end of the window -- an
;; average only appears once its whole block of channel samples has arrived.
(def result
  (loop [i 0, rows [], avg-by-tick {}, newest -1]
    (let [window-full (>= (count rows) window)]
      (if (or (>= i 200)
              (and window-full (>= newest (:tick (nth rows (dec window))))))
        [rows avg-by-tick]
        (let [rows (if window-full
                     rows
                     (let [chunk (.readWithDomain multi-reader chunk-size 1000)
                           ticks (.domainTicks chunk 0)]
                       (reduce (fn [rs k]
                                 (conj rs {:tick (aget ticks k)
                                           :v0 (aget (.doubleValues chunk 0) k)
                                           :v1 (aget (.doubleValues chunk 1) k)}))
                               rows (range (alength ticks)))))
              averages (.readWithDomain stream-reader chunk-size 1000)
              values (.doubleValues averages)
              aticks (.domainTicks averages)
              [avg-by-tick newest]
              (reduce (fn [[m nt] k]
                        [(assoc m (aget aticks k) (aget values k))
                         (max nt (aget aticks k))])
                      [avg-by-tick newest] (range (alength values)))]
          (recur (inc i) rows avg-by-tick newest))))))

(let [[rows avg-by-tick] result
      ;; The multi-reader only knows its common domain once it has read, so build
      ;; the tick->time converter now.
      channel-time (Daq/domainTimeConverter multi-reader)]
  (printf "%-14s%14s%14s%16s%n" "time" "channel 0" "channel 1" "avg(channel 0)")
  (dotimes [i (min window (count rows))]
    (let [row (nth rows i)
          average (get avg-by-tick (:tick row))]
      (printf "%-14s%14.4f%14.4f%16s%n"
              (.format fmt (.apply channel-time (:tick row)))
              (:v0 row) (:v1 row)
              (if average (format "%.4f" average) ""))))
  (flush))
