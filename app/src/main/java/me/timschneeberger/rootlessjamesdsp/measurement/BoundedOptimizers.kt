package me.timschneeberger.rootlessjamesdsp.measurement

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sign

/**
 * Детерминированный PRNG PCG32x2 и градиентно-свободные ограниченные (bounded)
 * оптимизаторы DE и CHIO.
 *
 * Порт с libMultivariateOpt (MIT, Copyright (c) 2021 James Fung,
 * https://github.com/james34602/libMultivariateOpt) по копии, вендорированной
 * в DDCToolbox (src/3rdparty/libMultivariateOpt):
 *  - GradientFree/gradfreeOpt/opt_alg.c: differentialEvolution(), CHIO()
 *  - libgenmath/rand_c.c: pcg32x2, c_rand, randi, randn (зиггурат), randperm
 *
 * Чистый Kotlin без JNI; последовательность случайных чисел и результат
 * детерминированы при фиксированном seed и не зависят от платформы.
 */
class Pcg32x2Random(seed1: ULong, seed2: ULong, seq1: ULong = 54uL, seq2: ULong = 84uL) {

    private class Pcg32(initstate: ULong, initseq: ULong) {
        var state: ULong = 0uL
        var inc: ULong = 0uL

        init {
            inc = (initseq shl 1) or 1uL
            next()
            state += initstate
            next()
        }

        fun next(): UInt {
            val oldstate = state
            state = oldstate * 6364136223846793005uL + inc
            val xorshifted = (((oldstate shr 18) xor oldstate) shr 27).toUInt()
            val rot = (oldstate shr 59).toUInt()
            return (xorshifted shr rot.toInt()) or
                (xorshifted shl ((-rot.toInt()) and 31))
        }
    }

    private val gen1: Pcg32
    private val gen2: Pcg32

    init {
        val mask = (0uL - 1uL) shr 1
        val s2 = if ((seq1 and mask) == (seq2 and mask)) seq2.inv() else seq2
        gen1 = Pcg32(seed1, seq1)
        gen2 = Pcg32(seed2, s2)
    }

    fun nextULong(): ULong = (gen1.next().toULong() shl 32) or gen2.next().toULong()

    /** Uniform [0, 1). */
    fun cRand(): Double = nextULong().toDouble() / (1uL shl 63).toDouble() * 0.5

    /** Uniform integer 1..value. */
    fun randi(value: Double): Double = floor(cRand() * value) + 1.0

    /** Fisher-Yates shuffle of 0 until n. */
    fun randperm(n: Int): IntArray {
        val a = IntArray(n) { it }
        if (n <= 1) return a
        for (i in n - 1 downTo 1) {
            val j = (nextULong() % (i + 1).toULong()).toInt()
            val t = a[j]
            a[j] = a[i]
            a[i] = t
        }
        return a
    }

    /** Standard normal via the reference ziggurat (256-level tables). */
    fun randn(): Double {
        while (true) {
            val u0 = gen1.next()
            val u1 = gen1.next()
            val i = ((u1 shr 24).toInt()) + 1
            val r = (((u0 shr 3).toDouble() * 1.6777216E7 +
                (u1 and 0xFFFFFFu).toInt().toDouble()) *
                2.2204460492503131E-16 - 1.0) * TBL1[i]
            if (abs(r) <= TBL1[i - 1]) return r
            if (i < 256) {
                val b = cRand()
                if (TBL2[i] + b * (TBL2[i - 1] - TBL2[i]) < exp(-0.5 * r * r)) return r
            } else {
                val x = tailSample()
                return if (r < 0.0) x - 3.65415288536101 else 3.65415288536101 - x
            }
        }
    }

    private fun tailSample(): Double {
        while (true) {
            var b = cRand()
            val x = ln(b) * 0.273661237329758
            b = cRand()
            if (-2.0 * ln(b) > x * x) return x
        }
    }

    private val TBL1 = doubleArrayOf(
        0.0, 0.215241895984875, 0.286174591792068, 0.335737519214422,
        0.375121332878378, 0.408389134611989, 0.43751840220787, 0.46363433679088,
        0.487443966139235, 0.50942332960209, 0.529909720661557, 0.549151702327164,
        0.567338257053817, 0.584616766106378, 0.601104617755991, 0.61689699000775,
        0.63207223638606, 0.646695714894993, 0.660822574244419, 0.674499822837293,
        0.687767892795788, 0.700661841106814, 0.713212285190975, 0.725446140909999,
        0.737387211434295, 0.749056662017815, 0.760473406430107, 0.771654424224568,
        0.782615023307232, 0.793369058840623, 0.80392911698997, 0.814306670135215,
        0.824512208752291, 0.834555354086381, 0.844444954909153, 0.854189171008163,
        0.863795545553308, 0.87327106808886, 0.882622229585165, 0.891855070732941,
        0.900975224461221, 0.909987953496718, 0.91889818364959, 0.927710533401999,
        0.936429340286575, 0.945058684468165, 0.953602409881086, 0.96206414322304,
        0.970447311064224, 0.978755155294224, 0.986990747099062, 0.99515699963509,
        1.00325667954467, 1.01129241744, 1.01926671746548, 1.02718196603564,
        1.03504043983344, 1.04284431314415, 1.05059566459093, 1.05829648333067,
        1.06594867476212, 1.07355406579244, 1.0811144097034, 1.08863139065398,
        1.09610662785202, 1.10354167942464, 1.11093804601357, 1.11829717411934,
        1.12562045921553, 1.13290924865253, 1.14016484436815, 1.14738850542085,
        1.15458145035993, 1.16174485944561, 1.16887987673083, 1.17598761201545,
        1.18306914268269, 1.19012551542669, 1.19715774787944, 1.20416683014438,
        1.2111537262437, 1.21811937548548, 1.22506469375653, 1.23199057474614,
        1.23889789110569, 1.24578749554863, 1.2526602218949, 1.25951688606371,
        1.26635828701823, 1.27318520766536, 1.27999841571382, 1.28679866449324,
        1.29358669373695, 1.30036323033084, 1.30712898903073, 1.31388467315022,
        1.32063097522106, 1.32736857762793, 1.33409815321936, 1.3408203658964,
        1.34753587118059, 1.35424531676263, 1.36094934303328, 1.36764858359748,
        1.37434366577317, 1.38103521107586, 1.38772383568998, 1.39441015092814,
        1.40109476367925, 1.4077782768464, 1.41446128977547, 1.42114439867531,
        1.42782819703026, 1.43451327600589, 1.44120022484872, 1.44788963128058,
        1.45458208188841, 1.46127816251028, 1.46797845861808, 1.47468355569786,
        1.48139403962819, 1.48811049705745, 1.49483351578049, 1.50156368511546,
        1.50830159628131, 1.51504784277671, 1.521803020761, 1.52856772943771,
        1.53534257144151, 1.542128153229, 1.54892508547417, 1.55573398346918,
        1.56255546753104, 1.56939016341512, 1.57623870273591, 1.58310172339603,
        1.58997987002419, 1.59687379442279, 1.60378415602609, 1.61071162236983,
        1.61765686957301, 1.62462058283303, 1.63160345693487, 1.63860619677555,
        1.64562951790478, 1.65267414708306, 1.65974082285818, 1.66683029616166,
        1.67394333092612, 1.68108070472517, 1.68824320943719, 1.69543165193456,
        1.70264685479992, 1.7098896570713, 1.71716091501782, 1.72446150294804,
        1.73179231405296, 1.73915426128591, 1.74654827828172, 1.75397532031767,
        1.76143636531891, 1.76893241491127, 1.77646449552452, 1.78403365954944,
        1.79164098655216, 1.79928758454972, 1.80697459135082, 1.81470317596628,
        1.82247454009388, 1.83028991968276, 1.83815058658281, 1.84605785028518,
        1.8540130597602, 1.86201760539967, 1.87007292107127, 1.878180486293,
        1.88634182853678, 1.8945585256707, 1.90283220855043, 1.91116456377125,
        1.91955733659319, 1.92801233405266, 1.93653142827569, 1.94511656000868,
        1.95376974238465, 1.96249306494436, 1.97128869793366, 1.98015889690048,
        1.98910600761744, 1.99813247135842, 2.00724083056053, 2.0164337349062,
        2.02571394786385, 2.03508435372962, 2.04454796521753, 2.05410793165065,
        2.06376754781173, 2.07353026351874, 2.0833996939983, 2.09337963113879,
        2.10347405571488, 2.11368715068665, 2.12402331568952, 2.13448718284602,
        2.14508363404789, 2.15581781987674, 2.16669518035431, 2.17772146774029,
        2.18890277162636, 2.20024554661128, 2.21175664288416, 2.22344334009251,
        2.23531338492992, 2.24737503294739, 2.25963709517379, 2.27210899022838,
        2.28480080272449, 2.29772334890286, 2.31088825060137, 2.32430801887113,
        2.33799614879653, 2.35196722737914, 2.36623705671729, 2.38082279517208,
        2.39574311978193, 2.41101841390112, 2.42667098493715, 2.44272531820036,
        2.4592083743347, 2.47614993967052, 2.49358304127105, 2.51154444162669,
        2.53007523215985, 2.54922155032478, 2.56903545268184, 2.58957598670829,
        2.61091051848882, 2.63311639363158, 2.65628303757674, 2.68051464328574,
        2.70593365612306, 2.73268535904401, 2.76094400527999, 2.79092117400193,
        2.82287739682644, 2.85713873087322, 2.89412105361341, 2.93436686720889,
        2.97860327988184, 3.02783779176959, 3.08352613200214, 3.147889289518,
        3.2245750520478, 3.32024473383983, 3.44927829856143, 3.65415288536101,
        3.91075795952492
    )

    private val TBL2 = doubleArrayOf(
        1.0, 0.977101701267673, 0.959879091800108, 0.9451989534423,
        0.932060075959231, 0.919991505039348, 0.908726440052131, 0.898095921898344,
        0.887984660755834, 0.878309655808918, 0.869008688036857, 0.860033621196332,
        0.851346258458678, 0.842915653112205, 0.834716292986884, 0.826726833946222,
        0.818929191603703, 0.811307874312656, 0.803849483170964, 0.796542330422959,
        0.789376143566025, 0.782341832654803, 0.775431304981187, 0.768637315798486,
        0.761953346836795, 0.755373506507096, 0.748892447219157, 0.742505296340151,
        0.736207598126863, 0.729995264561476, 0.72386453346863, 0.717811932630722,
        0.711834248878248, 0.705928501332754, 0.700091918136512, 0.694321916126117,
        0.688616083004672, 0.682972161644995, 0.677388036218774, 0.671861719897082,
        0.66639134390875, 0.660975147776663, 0.655611470579697, 0.650298743110817,
        0.645035480820822, 0.639820277453057, 0.634651799287624, 0.629528779924837,
        0.624450015547027, 0.619414360605834, 0.614420723888914, 0.609468064925773,
        0.604555390697468, 0.599681752619125, 0.594846243767987, 0.590047996332826,
        0.585286179263371, 0.580559996100791, 0.575868682972354, 0.571211506735253,
        0.566587763256165, 0.561996775814525, 0.557437893618766, 0.552910490425833,
        0.548413963255266, 0.543947731190026, 0.539511234256952, 0.535103932380458,
        0.530725304403662, 0.526374847171684, 0.522052074672322, 0.517756517229756,
        0.513487720747327, 0.509245245995748, 0.505028667943468, 0.500837575126149,
        0.49667156905249, 0.492530263643869, 0.488413284705458, 0.484320269426683,
        0.480250865909047, 0.476204732719506, 0.47218153846773, 0.468180961405694,
        0.464202689048174, 0.460246417812843, 0.456311852678716, 0.452398706861849,
        0.448506701507203, 0.444635565395739, 0.440785034665804, 0.436954852547985,
        0.433144769112652, 0.429354541029442, 0.425583931338022, 0.421832709229496,
        0.418100649837848, 0.414387534040891, 0.410693148270188, 0.407017284329473,
        0.403359739221114, 0.399720314980197, 0.396098818515832, 0.392495061459315,
        0.388908860018789, 0.385340034840077, 0.381788410873393, 0.378253817245619,
        0.374736087137891, 0.371235057668239, 0.367750569779032, 0.364282468129004,
        0.360830600989648, 0.357394820145781, 0.353974980800077, 0.350570941481406,
        0.347182563956794, 0.343809713146851, 0.340452257044522, 0.337110066637006,
        0.333783015830718, 0.330470981379163, 0.327173842813601, 0.323891482376391,
        0.320623784956905, 0.317370638029914, 0.314131931596337, 0.310907558126286,
        0.307697412504292, 0.30450139197665, 0.301319396100803, 0.298151326696685,
        0.294997087799962, 0.291856585617095, 0.288729728482183, 0.285616426815502,
        0.282516593083708, 0.279430141761638, 0.276356989295668, 0.273297054068577,
        0.270250256365875, 0.267216518343561, 0.264195763997261, 0.261187919132721,
        0.258192911337619, 0.255210669954662, 0.252241126055942, 0.249284212418529,
        0.246339863501264, 0.24340801542275, 0.240488605940501, 0.237581574431238,
        0.23468686187233, 0.231804410824339, 0.228934165414681, 0.226076071322381,
        0.223230075763918, 0.220396127480152, 0.217574176724331, 0.214764175251174,
        0.211966076307031, 0.209179834621125, 0.206405406397881, 0.203642749310335,
        0.200891822494657, 0.198152586545776, 0.195425003514135, 0.192709036903589,
        0.190004651670465, 0.187311814223801, 0.1846304924268, 0.181960655599523,
        0.179302274522848, 0.176655321443735, 0.174019770081839, 0.171395595637506,
        0.168782774801212, 0.166181285764482, 0.163591108232366, 0.161012223437511,
        0.158444614155925, 0.15588826472448, 0.153343161060263, 0.150809290681846,
        0.148286642732575, 0.145775208005994, 0.143274978973514, 0.140785949814445,
        0.138308116448551, 0.135841476571254, 0.133386029691669, 0.130941777173644,
        0.12850872228, 0.126086870220186, 0.123676228201597, 0.12127680548479,
        0.11888861344291, 0.116511665625611, 0.114145977827839, 0.111791568163838,
        0.109448457146812, 0.107116667774684, 0.104796225622487, 0.102487158941935,
        0.10018949876881, 0.0979032790388625, 0.095628536713009, 0.093365311912691,
        0.0911136480663738, 0.0888735920682759, 0.0866451944505581, 0.0844285095703535,
        0.082223595813203, 0.0800305158146631, 0.0778493367020961, 0.0756801303589272,
        0.0735229737139814, 0.0713779490588905, 0.0692451443970068, 0.0671246538277886,
        0.065016577971243, 0.0629210244377582, 0.06083810834954, 0.0587679529209339,
        0.0567106901062031, 0.0546664613248891, 0.0526354182767924, 0.0506177238609479,
        0.0486135532158687, 0.0466230949019305, 0.0446465522512946, 0.0426841449164746,
        0.0407361106559411, 0.0388027074045262, 0.0368842156885674, 0.0349809414617162,
        0.0330932194585786, 0.0312214171919203, 0.0293659397581334, 0.0275272356696031,
        0.0257058040085489, 0.0239022033057959, 0.0221170627073089, 0.0203510962300445,
        0.0186051212757247, 0.0168800831525432, 0.0151770883079353, 0.0134974506017399,
        0.0118427578579079, 0.0102149714397015, 0.00861658276939875, 0.00705087547137324,
        0.00552240329925101, 0.00403797259336304, 0.00260907274610216, 0.0012602859304986,
        0.000477467764609386
    )
}

/** Результат ограниченного оптимизатора: лучшая точка, её значение и число вызовов fn. */
class BoundedOptimizerResult(val x: DoubleArray, val f: Double, val evals: Int)

/**
 * Ограниченный Differential Evolution с Bezier-мутацией (порт differentialEvolution
 * из opt_alg.c). Популяция [groupSize] x [sampleSize]; за эпоху оценивается
 * [sampleSize] пробных векторов. Выход за границы: при rand > [probiBound]
 * вектор переназывается равномерно, иначе проецируется на границу.
 *
 * Возвращает лучшую найденную точку; она никогда не хуже лучшей точки
 * начальной популяции (жадные обновления + gbest только на улучшении).
 * Останов по [evalBudget] вызовов fn (детерминированный).
 */
fun differentialEvolution(
    fn: (DoubleArray) -> Double,
    initial: DoubleArray?,
    groupSize: Int,
    sampleSize: Int,
    probiBound: Double,
    dim: Int,
    lower: DoubleArray,
    upper: DoubleArray,
    maxIter: Int,
    rng: Pcg32x2Random,
    evalBudget: Int = Int.MAX_VALUE
): BoundedOptimizerResult {
    val popSize = groupSize * sampleSize
    val s = DoubleArray(popSize * dim)
    if (initial != null && initial.size >= popSize * dim) {
        System.arraycopy(initial, 0, s, 0, popSize * dim)
    } else {
        for (i in 0 until popSize)
            for (j in 0 until dim)
                s[i * dim + j] = rng.cRand() * (upper[j] - lower[j]) + lower[j]
    }

    var evals = 0
    fun evalRow(arr: DoubleArray, row: Int): Double {
        evals++
        return fn(arr.copyOfRange(row * dim, row * dim + dim))
    }

    val fitS = DoubleArray(popSize) { i -> evalRow(s, i) }
    var gminIdx = 0
    var gmin = fitS[0]
    for (i in 1 until popSize) {
        if (fitS[i] < gmin) {
            gmin = fitS[i]
            gminIdx = i
        }
    }
    val gbest = s.copyOfRange(gminIdx * dim, gminIdx * dim + dim)

    val trial = DoubleArray(sampleSize * dim)
    val trialF = DoubleArray(sampleSize)
    val p = DoubleArray(sampleSize * dim)
    val fitP = DoubleArray(sampleSize)
    val tbest = DoubleArray(sampleSize * dim)
    val dv2 = DoubleArray(sampleSize * dim)
    val map1 = ByteArray(sampleSize * dim)
    val map2 = ByteArray(sampleSize * dim)

    for (epk in 0 until maxIter) {
        if (evals + sampleSize > evalBudget) break

        // Два попарно различных перестановочных вектора по всей популяции
        var j1: IntArray
        var j2: IntArray
        var attempts = 0
        while (true) {
            j1 = rng.randperm(popSize)
            j2 = rng.randperm(popSize)
            var ok = true
            for (i in 0 until popSize) {
                if (j1[i] == j2[i]) {
                    ok = false
                    break
                }
            }
            if (ok || ++attempts >= 64) break
        }

        // Подматрица паттернов P и их пригодность
        val j0 = IntArray(sampleSize)
        val j2First = IntArray(sampleSize)
        for (i in 0 until sampleSize) {
            j0[i] = j1[i]
            j2First[i] = j2[i]
            fitP[i] = fitS[j0[i]]
            System.arraycopy(s, j0[i] * dim, p, i * dim, dim)
        }

        // Top-N-Best со смещением выбора к лучшим (куб случайной величины)
        val sortingIdx = Array(popSize) { it }.sortedBy { fitS[it] }
        for (i in 0 until sampleSize) {
            val idx = ceil(rng.cRand().pow(3.0) * popSize).toInt() - 1
            val src = sortingIdx[idx.coerceIn(0, popSize - 1)]
            System.arraycopy(s, src * dim, tbest, i * dim, dim)
        }

        // Bezier-мутация: четыре контрольные точки, переставляемые randperm(4)
        var bj1: IntArray
        var bj2: IntArray
        attempts = 0
        while (true) {
            bj1 = rng.randperm(sampleSize)
            bj2 = rng.randperm(sampleSize)
            var ok = true
            for (i in 0 until sampleSize) {
                if (bj1[i] == i || bj2[i] == i || bj1[i] == bj2[i]) {
                    ok = false
                    break
                }
            }
            if (ok || ++attempts >= 64) break
        }
        for (i in 0 until sampleSize) {
            val tmpIdx = rng.randperm(4)
            val t = rng.cRand()
            val tt = 1.0 - t
            val tt2 = tt * tt
            val t2 = t * t
            val b0 = tt2 * tt
            val b1 = t * 3.0 * tt2
            val b2 = t2 * 3.0 * tt
            val b3 = t2 * t
            for (j in 0 until dim) {
                val x = doubleArrayOf(
                    p[i * dim + j], p[bj1[i] * dim + j],
                    p[bj2[i] * dim + j], tbest[i * dim + j]
                )
                dv2[i * dim + j] = b0 * x[tmpIdx[0]] + b1 * x[tmpIdx[1]] +
                    b2 * x[tmpIdx[2]] + b3 * x[tmpIdx[3]]
            }
        }

        // Кроссоверные карты (две схемы разной резкости, выбор случайный)
        java.util.Arrays.fill(map1, 0.toByte())
        java.util.Arrays.fill(map2, 0.toByte())
        for (i in 0 until sampleSize) {
            var h = rng.randperm(dim)
            var w = rng.cRand().pow(rng.randi(7.0))
            var rang = ceil(w * dim).toInt()
            for (jj in 0 until rang) map1[i * dim + h[jj]] = 1
            h = rng.randperm(dim)
            w = 1.0 - rng.cRand().pow(rng.randi(7.0))
            rang = ceil(w * dim).toInt()
            for (jj in 0 until rang) map2[i * dim + h[jj]] = 1
        }
        val map = if (rng.cRand() < rng.cRand()) map1 else map2

        // Пробные векторы: T = P + map * F * (w1*(dv2 - P) + w2*(S[j2] - P))
        for (i in 0 until sampleSize) {
            val f1 = rng.randn()
            val f2 = rng.cRand()
            val f3 = rng.randn()
            val f4 = rng.randi(7.0)
            val t5 = 1.0 + f2
            var t6 = f3.pow(f4)
            if (abs(t5) > 1.0 && abs(t6) > 11.0) {
                t6 = if (t6 > 0.0) 11.0 else if (t6 < 0.0) -11.0 else 0.0
            }
            val f = f1 * t5.pow(t6)
            val w1 = rng.randn()
            val w2 = rng.randn()
            for (j in 0 until dim) {
                trial[i * dim + j] = p[i * dim + j] + map[i * dim + j].toInt() * f *
                    (w1 * (dv2[i * dim + j] - p[i * dim + j]) +
                        w2 * (s[j2First[i] * dim + j] - p[i * dim + j]))
            }
        }

        // Границы: с вероятностью (1 - probiBound) переназначение, иначе проекция
        if (rng.cRand() > probiBound) {
            for (i in 0 until sampleSize)
                for (j in 0 until dim)
                    if (trial[i * dim + j] < lower[j] || trial[i * dim + j] > upper[j])
                        trial[i * dim + j] = rng.cRand() * (upper[j] - lower[j]) + lower[j]
        } else {
            for (i in 0 until sampleSize)
                for (j in 0 until dim) {
                    if (trial[i * dim + j] < lower[j]) trial[i * dim + j] = lower[j]
                    if (trial[i * dim + j] > upper[j]) trial[i * dim + j] = upper[j]
                }
        }

        // Жадная замена паттернов улучшенными пробными векторами
        for (i in 0 until sampleSize) trialF[i] = evalRow(trial, i)
        for (i in 0 until sampleSize) {
            if (trialF[i] < fitP[i]) {
                System.arraycopy(trial, i * dim, p, i * dim, dim)
                fitP[i] = trialF[i]
            }
        }
        var bestVal = fitP[0]
        var bestIdx = 0
        for (i in 1 until sampleSize) {
            if (fitP[i] < bestVal) {
                bestVal = fitP[i]
                bestIdx = i
            }
        }
        if (bestVal < gmin) {
            gmin = bestVal
            System.arraycopy(p, bestIdx * dim, gbest, 0, dim)
        }
        for (i in 0 until sampleSize) {
            System.arraycopy(p, i * dim, s, j0[i] * dim, dim)
            fitS[j0[i]] = fitP[i]
        }
    }
    return BoundedOptimizerResult(gbest, gmin, evals)
}

private fun chioFitness(f: Double): Double =
    if (f >= 0.0) 1.0 / (f + 1.0) else 1.0 + abs(f)

/**
 * Ограниченный CHIO — Coronavirus Herd Immunity Optimizer (порт CHIO из opt_alg.c).
 * Популяция [popSize]; статусы решений: normal (0) / confirmed (1) / recovered (2),
 * возраст подтверждённых решений контролирует регенерацию.
 *
 * Возвращает лучшую реально оцененную точку (best-ever tracking): это устраняет
 * особенность эталона, где fitS отдельного решения остаётся устаревшим после
 * регенерации, и гарантирует, что результат не хуже лучшей точки начальной
 * популяции. Останов по [evalBudget] вызовов fn (детерминированный).
 */
fun chio(
    fn: (DoubleArray) -> Double,
    initial: DoubleArray?,
    popSize: Int,
    maxSolSurviveEpoch: Int,
    c0: Int,
    spreadingRate: Double,
    dim: Int,
    lower: DoubleArray,
    upper: DoubleArray,
    maxIter: Int,
    rng: Pcg32x2Random,
    evalBudget: Int = Int.MAX_VALUE
): BoundedOptimizerResult {
    val s = DoubleArray(popSize * dim)
    if (initial != null && initial.size >= popSize * dim) {
        System.arraycopy(initial, 0, s, 0, popSize * dim)
    } else {
        for (i in 0 until popSize)
            for (j in 0 until dim)
                s[i * dim + j] = rng.cRand() * (upper[j] - lower[j]) + lower[j]
    }

    var evals = 0
    fun evalRow(arr: DoubleArray, row: Int): Double {
        evals++
        return fn(arr.copyOfRange(row * dim, row * dim + dim))
    }

    val fitS = DoubleArray(popSize) { i -> evalRow(s, i) }
    val age = IntArray(popSize)
    val fitness = DoubleArray(popSize) { i -> chioFitness(fitS[i]) }
    val solFlag = ByteArray(popSize)
    for (i in 0 until c0) solFlag[(rng.cRand() * popSize).toInt()] = 1

    var bestF = fitS[0]
    var bestX = s.copyOfRange(0, dim)
    for (i in 1 until popSize) {
        if (fitS[i] < bestF) {
            bestF = fitS[i]
            bestX = s.copyOfRange(i * dim, i * dim + dim)
        }
    }

    val confirmedList = IntArray(popSize)
    val normList = IntArray(popSize)
    val newSol = DoubleArray(dim)

    for (epk in 0 until maxIter) {
        if (evals + popSize > evalBudget) break
        for (pol in 0 until popSize) {
            System.arraycopy(s, pol * dim, newSol, 0, dim)
            var infected = 0
            var numConfirmed = 0
            var numNormal = 0
            for (i in 0 until popSize) {
                if (solFlag[i] == 0.toByte()) normList[numNormal++] = i
                if (solFlag[i] == 1.toByte()) confirmedList[numConfirmed++] = i
            }
            var numRec = false
            for (i in 0 until popSize) {
                if (fitS[i] != 0.0 && solFlag[i] == 2.toByte()) {
                    numRec = true
                    break
                }
            }
            for (j in 0 until dim) {
                val r = rng.cRand()
                if (r < spreadingRate / 3 && numConfirmed > 0) {
                    val z = round((numConfirmed - 1) * rng.cRand()).toInt()
                    val zc = confirmedList[z]
                    newSol[j] += (s[pol * dim + j] - s[zc * dim + j]) * (rng.cRand() - 0.5) * 2.0
                    newSol[j] = newSol[j].coerceIn(lower[j], upper[j])
                    infected++
                } else if (r < spreadingRate / 2 && numNormal > 0) {
                    val z = round((numNormal - 1) * rng.cRand()).toInt()
                    val zn = normList[z]
                    newSol[j] += (s[pol * dim + j] - s[zn * dim + j]) * (rng.cRand() - 0.5) * 2.0
                    newSol[j] = newSol[j].coerceIn(lower[j], upper[j])
                } else if (r < spreadingRate && numRec) {
                    // Эталон здесь индексирует элемент первого решения (S[j]);
                    // перенесено как есть ради поведенческого соответствия
                    newSol[j] += (s[pol * dim + j] - s[j]) * (rng.cRand() - 0.5) * 2.0
                    newSol[j] = newSol[j].coerceIn(lower[j], upper[j])
                }
            }

            evals++
            val objValSol = fn(newSol)
            val fitnessSol = chioFitness(objValSol)

            if (fitS[pol] > objValSol) {
                System.arraycopy(newSol, 0, s, pol * dim, dim)
                fitness[pol] = fitnessSol
                fitS[pol] = objValSol
                if (objValSol < bestF) {
                    bestF = objValSol
                    bestX = newSol.copyOf()
                }
            } else if (solFlag[pol] == 1.toByte()) {
                age[pol]++
            }

            var mean = 0.0
            for (i in 0 until popSize) mean += fitness[i]
            mean /= popSize

            if (fitness[pol] < mean && solFlag[pol] == 0.toByte() && infected > 0) {
                solFlag[pol] = 1
                age[pol] = 1
            }
            if (fitness[pol] >= mean && solFlag[pol] == 1.toByte()) {
                solFlag[pol] = 2
                age[pol] = 0
            }
            if (age[pol] >= maxSolSurviveEpoch) {
                for (i in 0 until dim)
                    s[pol * dim + i] = rng.cRand() * (upper[i] - lower[i]) + lower[i]
                solFlag[pol] = 0
            }
        }
    }
    return BoundedOptimizerResult(bestX, bestF, evals)
}
