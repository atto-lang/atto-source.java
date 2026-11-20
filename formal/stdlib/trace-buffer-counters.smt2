; Trace.Buffer.record abstract counter transition, with one owner and no
; concurrent mutation. These are inductive one-step SAFETY obligations for
; any reachable state satisfying Inv, not a model of physical Array storage.
; The Event FIFO, method invocation and actual compiler/runtime require
; separate refinement evidence.
(set-logic QF_BV)
(define-fun zero () (_ BitVec 64) #x0000000000000000)
(define-fun one () (_ BitVec 64) #x0000000000000001)
(define-fun maximum-cap () (_ BitVec 64) #x0000000000010000)
(define-fun max-dropped () (_ BitVec 64) #x000000007fffffff)
(declare-const cap (_ BitVec 64))
(declare-const count (_ BitVec 64))
(declare-const dropped (_ BitVec 64))
(declare-const correlation (_ BitVec 64))
(declare-const phase (_ BitVec 64))
(declare-const duration (_ BitVec 64))
(define-fun Inv () Bool
  (and (bvsge cap one) (bvsle cap maximum-cap)
       (bvsge count zero) (bvsle count cap)
       (bvsge dropped zero) (bvsle dropped max-dropped)))
(define-fun Valid () Bool
  (and (bvsge correlation zero) (bvsgt phase zero)
       (bvsge duration zero)))
(define-fun Accepted () Bool (and Valid (bvslt count cap)))
(define-fun NextCount () (_ BitVec 64)
  (ite Accepted (bvadd count one) count))
(define-fun NextDropped () (_ BitVec 64)
  (ite (and Valid (bvsge count cap) (bvslt dropped max-dropped))
       (bvadd dropped one) dropped))

; A valid prestate always has a valid poststate (counter closure).
(push 1)
(assert (and Inv (or (bvslt NextCount zero) (bvsgt NextCount cap)
                     (bvslt NextDropped zero) (bvsgt NextDropped max-dropped))))
(check-sat)
(pop 1)
; Invalid events do not consume capacity or increase drop count.
(push 1)
(assert (and Inv (not Valid)
   (or Accepted (distinct NextCount count) (distinct NextDropped dropped))))
(check-sat)
(pop 1)
; A successful event increments count exactly once, not dropped.
(push 1)
(assert (and Inv Accepted
  (or (distinct NextCount (bvadd count one))
      (distinct NextDropped dropped))))
(check-sat)
(pop 1)
; A full buffer rejects; dropped increases iff below saturation.
(push 1)
(assert (and Inv Valid (= count cap)
  (or Accepted (distinct NextCount count)
    (distinct NextDropped
      (ite (bvslt dropped max-dropped) (bvadd dropped one) dropped)))))
(check-sat)
(pop 1)
; Success exactly matches valid input with free room.
(push 1)
(assert (and Inv (distinct Accepted (and Valid (bvslt count cap)))))
(check-sat)
(pop 1)
; Neither count nor dropped decreases in a legal step.
(push 1)
(assert (and Inv (or (bvslt NextCount count) (bvslt NextDropped dropped))))
(check-sat)
(pop 1)
; The full-buffer failure path never expands capacity.
(push 1)
(assert (and Inv (= count cap) (bvsgt NextCount cap)))
(check-sat)
(pop 1)
; At saturation, the drop count stays unchanged even with a valid event.
(push 1)
(assert (and Inv Valid (= count cap) (= dropped max-dropped)
             (distinct NextDropped max-dropped)))
(check-sat)
(pop 1)

; Constructor base case: after a valid capacity is accepted, empty counters
; establish Inv. This is only a modeled base case, not guest constructor proof.
(push 1)
(assert (and (bvsge cap one) (bvsle cap maximum-cap)
  (= count zero) (= dropped zero) (not Inv)))
(check-sat)
(pop 1)

; Negative controls: a <= admission test erroneously admits at full
; capacity; an unguarded dropped++ exceeds the declared saturation cap.
; Although it breaches the 2^31-1 application limit, it does NOT overflow
; the model's 64-bit signed integer representation at this boundary.
(push 1)
(assert (and Inv Valid (= count cap)
  (and Valid (bvsle count cap))))
(check-sat)
(pop 1)
(push 1)
(assert (and Inv Valid (= count cap) (= dropped max-dropped)
  (bvsgt (bvadd dropped one) max-dropped)))
(check-sat)
(pop 1)
; Mandatory SAT control: permitting zero capacity violates the base Inv.
(push 1)
(assert (and (= cap zero) (= count zero) (= dropped zero) (not Inv)))
(check-sat)
(pop 1)
