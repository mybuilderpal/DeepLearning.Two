package com.thoughtworks.deeplearning

import language.existentials
import language.implicitConversions
import language.higherKinds
import scala.annotation.elidable

object Layer {

  private[deeplearning] trait CloseableOnce extends AutoCloseable {

    private[CloseableOnce] final class ClosingFlag {
      var closed = false

      @elidable(elidable.ASSERTION)
      def close() = {
        assert(!closed)
        closed = true
      }

      @elidable(elidable.ASSERTION)
      def assertClosed() = {
        assert(closed)
      }
    }

    // FIXME: @elidable should only be used for def
    @elidable(elidable.ASSERTION)
    private val closingFlag = new ClosingFlag

    override def close() = {
      closingFlag.close()
    }

    override protected def finalize(): Unit = {
      closingFlag.assertClosed()
    }
  }

  object Tape {

    /**
      * A [[Tape]] whose [[Tape.Data Data]] and [[Tape.Delta Delta]] are specified from type parameter `Data0` and `Delta0`.
      *
      * @see [[https://gigiigig.github.io/posts/2015/09/13/aux-pattern.html aux pattern]]
      * @see [[http://www.vlachjosef.com/aux-pattern-evolution/ aux pattern evolution]]
      */
    type Aux[+Data0, -Delta0] = Tape {
      type Data <: Data0
      type Delta >: Delta0
    }

  }

  /**
    * `Tape` is a intermediate data structure generated by the [[Layer#forward forward]] of neural network , which contains result of the `forward`, and can perform [[backward]] for back-propagation.
    *
    * @note [[close]] method must be called when this [[Tape]] will not be used any more.
    * @see [[https://en.wikipedia.org/wiki/Automatic_differentiation tape auto differentiation]]
    */
  trait Tape extends AutoCloseable {

    /**
      * Type of the result of [[Layer#forward forward]] pass.
      *
      * @see [[value]]
      */
    type Data

    /**
      * Type of the information passing in [[backward]] pass, usually the partial derivative of [[Data]].
      *
      * @see [[backward]]
      */
    type Delta

    /**
      * Returns a new [[Layer.Tape Tape]] that shares the same [[value]] and [[backward]] behavior with this `Tape`.
      *
      * @note The newly created `Tape` and this `Tape` must be [[close]]d independently.
      */
    def duplicate(): Tape.Aux[Data, Delta]

    protected def forceBackward(delta: Delta): Unit

    def isTrainable: Boolean

    /**
      * invoke `forceBackward` if [[Tape.isTrainable isTrainable]] is `true`
      *
      * @see [[Delta]]
      */
    @inline
    final def backward(delta: => Delta): Unit = {
      if (isTrainable) {
        forceBackward(delta)
      }
    }

    /**
      * Value of the result of [[Layer#forward forward]] pass.
      *
      * @see [[Data]]
      */
    def value: Data
  }

  /**
    * A [[Layer]] whose [[Layer.Input Input]] and [[Layer.Output Output]] are specified from type parameter `Input0` and `Output0`.
    *
    * @see [[https://gigiigig.github.io/posts/2015/09/13/aux-pattern.html aux pattern]]
    * @see [[http://www.vlachjosef.com/aux-pattern-evolution/ aux pattern evolution]]
    */
  type Aux[-Input0 <: Tape, +Output0 <: Tape] =
    Layer {
      type Input >: Input0
      type Output <: Output0
    }

}

/**
  * A `Layer` represents a neural network. Each `Layer` can be included in as a sub-network of another `Layer`, forming a more complex neural network. The nesting structure of `Layer` can be used to represent mathematical expression or Coarse-grain neural network structure.
  * When a neural network is written, the most elements in it are placeholders. When network training begins, the data enter into the network.
  *
  * === Tree structure of `Layer` ===
  *
  * {{{
  * val myLayer: Layer.Aux[Tape.Aux[Double, Double], Tape.Aux[Double, Double]] = {
  *   Times(
  *     Plus(
  *       Literal(1.0),
  *       Identity[Double, Double]()
  *     ),
  *     Weight(2.0)
  *   )
  * }
  * }}}
  *
  * The above mathematical expression with equivalent codes can be written, by [[Symbolic]], as: `(1.0 + x) * 2.0.toWeight`. `2.0.toWeight` represents a variable, of which the initial value is `2`. The value updates during neural network iteration.
  *
  * Both [[com.thoughtworks.deeplearning.DifferentiableDouble.Layers.Times Times]] and [[com.thoughtworks.deeplearning.DifferentiableDouble.Layers.Plus Plus]] are `case class`es, therefore, `myLayer` is a tree in nested structure consisted of the case class. `Times` and `Plus` are placeholders.
  *
  * [[com.thoughtworks.deeplearning.DifferentiableDouble.Layers.Weight Weight]] is a `Layer` containing weight, of which the initial value is `2.0`.
  *
  * [[com.thoughtworks.deeplearning.Symbolic.Layers.Identity Identity]] is a `Layer` with equal input and output, which return the same input back. The `Identity` here is the placeholder of `Input`.
  *
  * [[com.thoughtworks.deeplearning.Symbolic.Layers.Literal Literal]] is a `Layer` containing a constant.
  *
  * === Iteration ===
  *
  * Each training of the network is called as an iteration, including two stages: [[forward]] and [[com.thoughtworks.deeplearning.Layer.Tape#backward backward]], forming a complete process of [[https://en.wikipedia.org/wiki/Backpropagation]]
  *
  * ==== Forward ====
  *
  * When invoking `forward` in `Layer.Aux[A,B]`, `A` is input type, `B` is output type, and both `A` and `B` are [[com.thoughtworks.deeplearning.Layer.Tape Tape]]. Now, the codes are interpreted segment by segment as follows.
  *
  * For example:
  * {{{
  * val inputTape: Tape.Aux[Double, Double] = Literal(a)
  * val outputTape = myLayer.forward(inputTape)
  * }}}
  *
  *
  * When invoking `myLayer.forward(inputData)`, `forward` of `Times` shall be invoked first, of which the pseudo codes are as follows:
  * {{{
  * final case class Times(operand1: Layer, operand2: Layer) extends Layer {
  *   def forward(inputData: Tape): Output = {
  *     val upstream1 = operand1.forward(input)
  *     val upstream2 = operand2.forward(input)
  *     new Output(upstream1, upstream2)
  *   }
  *   final class Output(upstream1: Tape, upstream2: Tape) extends Tape { ... }
  * }
  * }}}
  *
  * It is a `Plus` at `myLayer.operand1`, and a `Weight` at `myLayer.operand2`. Therefore, `upstream1` and `upstream2` are the results of `forward` of `operand1` and `operand2` respectively.
  *
  * In a similar way, the `forward` code of `Plus` is similar to `forward` of `Times`. During the invoking for `forward` of `Plus`, [[com.thoughtworks.deeplearning.DifferentiableDouble.Layers.Plus#operand1 operand1]] is `Literal`, and [[com.thoughtworks.deeplearning.DifferentiableDouble.Layers.Plus.operand2 operand2]] is `Identity`. At this point, `forward` of `Literal` and `Identity` of each are invoked respectively.
  *
  * During the invoking for `forward` of `Identity`, the same input will be returned. The pseudo code for `forward` of `Identity` is as follows:
  *
  * {{{
  * def forward(inputTape: Tape.Aux[Double, Double]) = inputTape
  * }}}
  * Therefore, `Input` is the `x` in mathematical expression `(1.0 + x) * 2.0.toWeight`, and in this way, `Input` is propagated to the neural network.
  *
  * The return value `outputTape` of `myLayer.forward` is in `Tape` type. Therefore, a tree consisted of `Tape` will be generated finally with the structure similar to that of `myLayer`.
  *
  * Therefore, via layer-by-layer propagation, the same `myLayer.forward(inputTape)` is finally returned by `Identity` and combined into the newly generated `Tape` tree.
  *
  * The computation result including `forward` of `outputTape` can be used for `outputTape`, for example:
  * {{{
  *    try {
  *      val loss = outputTape.value
  *      outputTape.backward(loss)
  *      loss
  *    } finally {
  *      outputTape.close()
  *    }
  * }}}
  *
  * `outputTape.value` is the computation result of mathematical expression `(1.0 + x) * 2.0.toWeight`
  * ==== Backward ====
  *
  * `outputTape.backward` is the `outputTape.backward` of `Times.Output`, of which the pseudo code is as follows:
  * {{{
  * case class Times(operand1: Layer, operand2: Layer) extends Layer {
  *   def forward = ...
  *   class Output(upstream1, upstream2) extends Tape {
  *     private def upstreamDelta1(outputDelta: Double) = ???
  *     private def upstreamDelta2(outputDelta: Double) = ???
  *     override protected def backward(outputDelta: Double): Unit = {
  *       upstream1.backward(upstreamDelta1(outputDelta))
  *       upstream2.backward(upstreamDelta2(outputDelta))
  *     }
  *   }
  * }
  * }}}
  *
  * `outputTape.upstream1` and `outputTape.upstream2` are the results of `forward` of `operand1` and `operand2` respectively, which are followed by `backward` of `outputTape.upstream1` and `outputTape.upstream2`.
  *
  *
  * In a similar way, the `backward` code of `Plus`` is similar to `backward` of `Times`. During the invoking for `backward` of `Plus`, `upstream1` and `upstream2` are the results of `forward` of `Literal` and `Identity` respectively. At this point, `backward` of `upstream1` and `upstream2` of each are invoked respectively.
  *
  * `Weight` updates during `backward`, refer to [[com.thoughtworks.deeplearning.DifferentiableDouble.Optimizers.LearningRate#updateDouble updateDouble]]
  *
  * === Aux & Symbolic API ===
  *
  * `Layer.Aux[A,B]` represents that `Input` is of `A` type, and `Output` is of `B` type. `Tape.Aux[C,D]` represents that `Data` is of `C` type, and `Delta` is of `D` type.
  *
  * `Layer.Aux` and `Type.Aux` can be combined for use. For example, `Layer.Aux[Tape.Aux[A,B]`,`Tape.Aux[C,D]]` can be used to represent that the input type of a `layer` is a `Tape`, and the data type of this `Tape` is `A`, `delta` type is `B`; the output type of a `layer` is a `Tape`, and the data type of this `Tape` is `C`, `delta` type is `D`.
  *
  * [[https://gigiigig.github.io/posts/2015/09/13/aux-pattern.html Aux]] is a design pattern which realized [[https://www.scala-lang.org/files/archive/spec/2.12/03-types.html type refinement]] and can be used to limit the range of type parameters.
  *
  * Generally, we will not handwrite `Aux` type, because we can use `Symbolic` to describe the same type. For example, when being used for symbolic method internal variable and return value: `Layer.Aux[Tape.Aux[INDArray, INDArray], Tape.Aux[INDArray, INDArray` and `INDArray @Symbolic` are equivalent, so we usually use `Symbolic` to replace the writing method of `Aux`.
  *
  * @see [[https://gigiigig.github.io/posts/2015/09/13/aux-pattern.html aux pattern]]
  * @see [[http://www.vlachjosef.com/aux-pattern-evolution/ aux pattern evolution]]
  * @see [[https://www.scala-lang.org/files/archive/spec/2.12/03-types.html type refinement]]
  * @see [[https://en.wikipedia.org/wiki/Backpropagation Backpropagation]]
  * @see [[Symbolic]]
  */
trait Layer {

  import Layer._

  type Input <: Tape

  type Output <: Tape

  def forward(input: Input): Output

}
