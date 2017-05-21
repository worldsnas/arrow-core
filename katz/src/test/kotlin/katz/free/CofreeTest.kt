package katz

import io.kotlintest.KTestJUnitRunner
import io.kotlintest.matchers.shouldBe
import katz.Cofree.Companion.unfold
import katz.ListT.ListF
import katz.Option.None
import katz.Option.Some
import org.junit.runner.RunWith


// Unsafe functor for this test only
class ListT<out A>(val all: List<A>) : HK<ListF, A> {
    class ListF private constructor()

    companion object : Functor<ListF> {
        override fun <A, B> map(fa: HK<ListF, A>, f: (A) -> B): HK<ListF, B> =
                ListT((fa as ListT<A>).all.map(f))

    }
}

@RunWith(KTestJUnitRunner::class)
class CofreeTest : UnitSpec() {

    init {
        "tailForced should evaluate and return" {
            val sideEffect = SideEffect()
            val start: Cofree<Id.F, Int> = unfold(sideEffect.counter, { sideEffect.increment(); Id(it + 1) })
            sideEffect.counter shouldBe 0
            start.tailForced()
            sideEffect.counter shouldBe 1
        }

        "runTail should run once and return" {
            val sideEffect = SideEffect()
            val start: Cofree<Id.F, Int> = unfold(sideEffect.counter, { sideEffect.increment(); Id(it) })
            sideEffect.counter shouldBe 0
            start.runTail()
            sideEffect.counter shouldBe 1
        }

        "run should fold until completion" {
            val sideEffect = SideEffect()
            val start: Cofree<Option.F, Int> = unfold(sideEffect.counter, {
                sideEffect.increment()
                if (it == 5) None else Some(it + 1)
            })
            sideEffect.counter shouldBe 0
            start.run()
            sideEffect.counter shouldBe 6
            start.extract() shouldBe 0
        }

        "run with an stack-unsafe monad should blow up the stack" {
            try {
                val limit = 10000
                val counter = SideEffect()
                val startThousands: Cofree<Option.F, Int> = unfold(counter.counter, {
                    counter.increment()
                    if (it == limit) None else Some(it + 1)
                })
                startThousands.run()
                throw AssertionError("Run should overflow on a stack-unsafe monad")
            } catch (e: StackOverflowError) {
                // Expected. For stack safety, use cata and cataM instead
            }
        }

        "run with an stack-safe monad should not blow up the stack" {
            val counter = SideEffect()
            val startThousands: Cofree<Eval.F, Int> = unfold(counter.counter, {
                counter.increment()
                Eval.now(it + 1)
            })
            startThousands.run()
            counter.counter shouldBe 1
        }

        val startHundred: Cofree<Option.F, Int> = unfold(0, { if (it == 100) None else Some(it + 1) }, Option)

        "mapBranchingRoot should modify the value of the functor" {
            val mapped = startHundred.mapBranchingRoot(object : FunctionK<Option.F, Option.F> {
                override fun <A> invoke(fa: HK<Option.F, A>): HK<Option.F, A> =
                        None
            })
            val expected = NonEmptyList.of(0)
            cofreeOptionToNel(mapped) shouldBe expected
        }

        "mapBranchingS/T should recur over S and T respectively" {
            val mappedS = startHundred.mapBranchingS(optionToList, ListT)
            val mappedT = startHundred.mapBranchingT(optionToList, ListT)
            val expected = NonEmptyList.fromListUnsafe((0..100).toList())
            cofreeListToNel(mappedS) shouldBe expected
            cofreeListToNel(mappedT) shouldBe expected
        }

        "cata should traverse the structure in a stack-safe way" {
            val cata: NonEmptyList<Int> = startHundred.cata<Option.F, Int, NonEmptyList<Int>>(
                    { i, lb -> Eval.now(NonEmptyList(i, lb.ev().fold({ emptyList<Int>() }, { it.all }))) },
                    OptionTraverse
            ).value()

            val expected = NonEmptyList.fromListUnsafe((0..100).toList())

            cata shouldBe expected
        }

        "cataM should traverse the structure in a stack-safe way on a monad" {
            val folder: (Int, HK<Option.F, NonEmptyList<Int>>) -> EvalOption<NonEmptyList<Int>> = {
                i, lb ->
                if (i <= 100) OptionT.pure(NonEmptyList(i, lb.ev().fold({ emptyList<Int>() }, { it.all }))) else OptionT.none()
            }
            val inclusion = object : FunctionK<Eval.F, EvailOptionF> {
                override fun <A> invoke(fa: HK<Eval.F, A>): HK<EvailOptionF, A> =
                        OptionT(fa.ev().map { Some(it) })
            }
            val cataHundred = startHundred.cataM(folder, inclusion, OptionTraverse, OptionTMonad()).ev().value.ev().value()
            val newCof = Cofree(Option, 101, Eval.now(Some(startHundred)))
            val cataHundredOne = newCof.cataM(folder, inclusion, OptionTraverse, OptionTMonad()).ev().value.ev().value()

            cataHundred shouldBe Some(NonEmptyList.fromListUnsafe((0..100).toList()))
            cataHundredOne shouldBe None
        }

        "cofree should cobind correctly" {
            val offset = 0
            val limit = 10
            fun stackSafeProgram(loops: SideEffect): Int =
                    CofreeComonad<Option.F>().cobinding {
                        val program = unfold(offset, {
                            loops.increment()
                            if (it == limit) None else Some(it + 1)
                        })
                        val value: Int = !program.run()
                        val tail: Int = !program.runTail()
                        yields(value + tail)
                    }

            val loops = SideEffect()
            val program = stackSafeProgram(loops)
            program shouldBe 0
            loops.counter shouldBe limit + 1
        }
    }
}

typealias EvalOption<A> = OptionTKind<Eval.F, A>

typealias EvailOptionF = OptionTF<Eval.F>