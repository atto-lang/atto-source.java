; 8-bit SIGNED analogue of compute.valid_exact_buffer_layout, not
; an i64 theorem and not a source-to-SMT compiler. BV8 makes the
; arithmetic lemma fast/exhaustive; executable i64 checks are separate.
(set-logic QF_BV)
(define-fun z () (_ BitVec 8) #x00)
(define-fun one () (_ BitVec 8) #x01)
(define-fun mx () (_ BitVec 8) #x7f)
(declare-const elem (_ BitVec 8))
(declare-const len (_ BitVec 8))
(declare-const align (_ BitVec 8))
(declare-const actual (_ BitVec 8))
(define-fun valid () Bool (or
  (and (bvsgt elem z) (= len z) (bvsgt align z) (= actual z))
  (and (bvsgt elem z) (bvsgt len z) (bvsgt align z) (bvsge actual z)
    (bvule elem (bvudiv mx len)) (= actual (bvmul elem len)))))
(define-fun prod16 () (_ BitVec 16) (bvmul ((_ zero_extend 8) elem) ((_ zero_extend 8) len)))
(define-fun max16 () (_ BitVec 16) ((_ zero_extend 8) mx))
; An admitted length/product is representable as a positive signed 8-bit value.
(push 1)
(assert (and valid (bvugt prod16 max16)))
(check-sat)
(pop 1)
; Low-width product has no wraparound or truncation when admitted.
(push 1)
(assert (and valid (distinct ((_ zero_extend 8) actual) prod16)))
(check-sat)
(pop 1)
; Zero-length admission never authorizes nonzero byte length.
(push 1)
(assert (and valid (= len z) (distinct actual z)))
(check-sat)
(pop 1)
; SAT negative controls: inconsistent count and overflowing product rejected.
(push 1)
(assert (and (= elem #x08) (= len #x03) (= align #x08) (= actual one) (not valid)))
(check-sat)
(pop 1)
(push 1)
(assert (and (= elem mx) (= len #x02) (= align one) (= actual one)
 (bvugt prod16 max16) (not valid)))
(check-sat)
(pop 1)
