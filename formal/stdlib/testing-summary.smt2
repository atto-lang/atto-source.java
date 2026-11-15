; Abstract test-runner accounting for stdlib/testing.ores. One processed test
; advances a single-owner counter state. Kind: 0 filtered, 1 skipped,
; 2 passed, 3 failed. A bounded suite (<=1024 visited cases) excludes
; arithmetic overflow and makes the inductive step meaningful on BV64.
; This says nothing about callback execution, machine output or fair scheduling.
(set-logic QF_BV)
(define-fun z () (_ BitVec 64) #x0000000000000000)
(define-fun one () (_ BitVec 64) #x0000000000000001)
(define-fun limit () (_ BitVec 64) #x0000000000000400)
(declare-const seen (_ BitVec 64))
(declare-const total (_ BitVec 64))
(declare-const passed (_ BitVec 64))
(declare-const failed (_ BitVec 64))
(declare-const skipped (_ BitVec 64))
(declare-const filtered (_ BitVec 64))
(declare-const halted Bool)
(declare-const failfast Bool)
(declare-const kind (_ BitVec 8))
(define-fun valid-kind () Bool (bvule kind #x03))
(define-fun inv () Bool
  (and (bvule seen limit) (bvule total limit)
       (bvule passed limit) (bvule failed limit)
       (bvule skipped limit) (bvule filtered limit)
       (= seen (bvadd total filtered))
       (= total (bvadd (bvadd passed failed) skipped))))
(define-fun step () Bool (and inv (not halted) (bvult seen limit) valid-kind))
(define-fun next-seen () (_ BitVec 64) (bvadd seen one))
(define-fun next-total () (_ BitVec 64) (ite (= kind #x00) total (bvadd total one)))
(define-fun next-filtered () (_ BitVec 64) (ite (= kind #x00) (bvadd filtered one) filtered))
(define-fun next-skipped () (_ BitVec 64) (ite (= kind #x01) (bvadd skipped one) skipped))
(define-fun next-passed () (_ BitVec 64) (ite (= kind #x02) (bvadd passed one) passed))
(define-fun next-failed () (_ BitVec 64) (ite (= kind #x03) (bvadd failed one) failed))
(define-fun next-halted () Bool (or halted (and failfast (= kind #x03))))
(define-fun next-inv () Bool
  (and (bvule next-seen limit) (bvule next-total limit)
       (bvule next-passed limit) (bvule next-failed limit)
       (bvule next-skipped limit) (bvule next-filtered limit)
       (= next-seen (bvadd next-total next-filtered))
       (= next-total (bvadd (bvadd next-passed next-failed) next-skipped))))
; The invariant is inductive for each processed case.
(push 1)
(assert (and step (not next-inv)))
(check-sat)
(pop 1)
; Filtering doesn't count as a selected test.
(push 1)
(assert (and step (= kind #x00) (distinct next-total total)))
(check-sat)
(pop 1)
; A skip affects only skip among selected outcomes.
(push 1)
(assert (and step (= kind #x01) (or
  (distinct next-skipped (bvadd skipped one))
  (distinct next-passed passed) (distinct next-failed failed))))
(check-sat)
(pop 1)
; Pass and fail each affect their own counter, never another outcome.
(push 1)
(assert (and step (= kind #x02) (or
  (distinct next-passed (bvadd passed one))
  (distinct next-skipped skipped) (distinct next-failed failed))))
(check-sat)
(pop 1)
(push 1)
(assert (and step (= kind #x03) (or
  (distinct next-failed (bvadd failed one))
  (distinct next-skipped skipped) (distinct next-passed passed))))
(check-sat)
(pop 1)
; A failure under fail-fast terminates the modeled iteration.
(push 1)
(assert (and step failfast (= kind #x03) (not next-halted)))
(check-sat)
(pop 1)
; Every processed case increments seen by exactly one without wrapping.
(push 1)
(assert (and step (bvule next-seen seen)))
(check-sat)
(pop 1)
(push 1)
(assert (and step (bvugt next-seen limit)))
(check-sat)
(pop 1)
; The zeroed suite before visiting any case establishes the invariant.
(push 1)
(assert (and (= seen z) (= total z) (= passed z) (= failed z)
  (= skipped z) (= filtered z) (not halted) (not inv)))
(check-sat)
(pop 1)
; Mutation controls: an uncounted failure and a double-counted skip break
; the conservation invariant for an otherwise valid step.
(push 1)
(assert (and step (= kind #x03) (distinct next-total
  (bvadd (bvadd next-passed failed) next-skipped))))
(check-sat)
(pop 1)
(push 1)
(assert (and step (= kind #x01)
  (distinct next-seen (bvadd next-total (bvadd filtered one)))))
(check-sat)
(pop 1)
; Mandatory SAT negative base: a spurious initial filtered case is invalid.
(push 1)
(assert (and (= seen z) (= total z) (= passed z) (= failed z)
  (= skipped z) (= filtered one) (not halted) (not inv)))
(check-sat)
(pop 1)
