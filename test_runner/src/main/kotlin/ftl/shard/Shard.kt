package ftl.shard

import com.google.common.annotations.VisibleForTesting
import ftl.args.AndroidArgs
import ftl.args.IArgs
import ftl.args.IosArgs
import ftl.reports.xml.model.JUnitTestCase
import ftl.reports.xml.model.JUnitTestResult
import ftl.util.FlankTestMethod
import ftl.util.FlankFatalError
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

data class TestMethod(
    val name: String,
    val time: Double
)

data class TestShard(
    var time: Double,
    val testMethods: MutableList<TestMethod>
)

/** List of shards containing test names as a string. */
typealias StringShards = MutableList<MutableList<String>>

fun List<TestShard>.stringShards(): StringShards {
    return this.map { shard ->
        shard.testMethods.map { it.name }.toMutableList()
    }.toMutableList()
}

/*

iOS:
<dict>
  <key>StudentUITests</key>
  <key>OnlyTestIdentifiers</key>
    <array>
      <string>GREYError/testCaseClassName</string>

Android:
class com.foo.ClassName#testMethodToSkip

*/

object Shard {
    // When a test does not have previous results to reference, fall back to this run time.
    @VisibleForTesting
    const val DEFAULT_TEST_TIME_SEC = 120.0
    private const val IGNORE_TEST_TIME = 0.0

    private fun JUnitTestCase.androidKey(): String {
        return "class $classname#$name"
    }

    private fun JUnitTestCase.iosKey(): String {
        // FTL iOS XML appends `()` to each test name. ex: `testBasicSelection()`
        // xctestrun file requires classname/name with no `()`
        val testName = name?.substringBefore('(')
        return "$classname/$testName"
    }

    // take in the XML with timing info then return the shard count based on execution time
    fun shardCountByTime(
        testsToRun: List<FlankTestMethod>,
        oldTestResult: JUnitTestResult,
        args: IArgs
    ): Int {
        if (args.shardTime == -1) return -1
        if (args.shardTime < -1 || args.shardTime == 0) throw FlankFatalError("Invalid shard time ${args.shardTime}")

        val oldDurations = createTestMethodDurationMap(oldTestResult, args)
        val testsTotalTime = testsToRun.sumByDouble { if (it.ignored) IGNORE_TEST_TIME else oldDurations[it.testName] ?: DEFAULT_TEST_TIME_SEC }

        val shardsByTime = ceil(testsTotalTime / args.shardTime).toInt()

        // Use a single shard unless total test time is greater than shardTime.
        if (testsTotalTime <= args.shardTime) {
            return 1
        }

        // If there is no limit, use the calculated amount
        if (args.maxTestShards == -1) {
            return shardsByTime
        }

        // We need to respect the maxTestShards
        val shardCount = min(shardsByTime, args.maxTestShards)

        if (shardCount <= 0) throw FlankFatalError("Invalid shard count $shardCount")
        return shardCount
    }

    // take in the XML with timing info then return list of shards based on the amount of shards to use
    fun createShardsByShardCount(
        testsToRun: List<FlankTestMethod>,
        oldTestResult: JUnitTestResult,
        args: IArgs,
        forcedShardCount: Int = -1
    ): List<TestShard> {
        if (forcedShardCount < -1 || forcedShardCount == 0) throw FlankFatalError("Invalid forcedShardCount value $forcedShardCount")

        val maxShards = if (forcedShardCount == -1) args.maxTestShards else forcedShardCount
        val previousMethodDurations = createTestMethodDurationMap(oldTestResult, args)

        var cacheMiss = 0
        val testCases: List<TestMethod> = testsToRun
            .map {
                TestMethod(
                    name = it.testName,
                    time = if (it.ignored) IGNORE_TEST_TIME else previousMethodDurations[it.testName] ?: DEFAULT_TEST_TIME_SEC.also {
                        cacheMiss += 1
                    }
                )
            }
            // We want to iterate over testcase going from slowest to fastest
            .sortedByDescending(TestMethod::time)

        // Ugly hotfix for case when all test cases are annotated with @Ignore
        // we need to filter them because they have time == 0.0 which cause empty shards creation, few lines later
        // and we don't need additional shards for ignored tests.
        val testCount =
            if (testCases.isEmpty()) 0
            else testCases.filter { it.time > 0.0 }.takeIf { it.isNotEmpty() }?.size ?: 1

        // If maxShards is infinite or we have more shards than tests, let's match it
        val shardsCount = if (maxShards == -1 || maxShards > testCount) testCount else maxShards

        // Create the list of shards we will return
        if (shardsCount <= 0) {
            val platform = if (args is IosArgs) "ios" else "android"
            throw FlankFatalError(
                """Invalid shard count. To debug try: flank $platform run --dump-shards
                    | args.maxTestShards: ${args.maxTestShards}
                    | forcedShardCount: $forcedShardCount 
                    | testCount: $testCount 
                    | maxShards: $maxShards 
                    | shardsCount: $shardsCount""".trimMargin()
            )
        }
        var shards = List(shardsCount) { TestShard(0.0, mutableListOf()) }

        testCases.forEach { testMethod ->
            val shard = shards.first()

            shard.testMethods.add(testMethod)
            shard.time += testMethod.time

            // Sort the shards to keep the most empty shard first
            shards = shards.sortedBy { it.time }
        }

        val allTests = testsToRun.size // zero when test targets is empty
        val cacheHit = allTests - cacheMiss
        val cachePercent = if (allTests == 0) 0.0 else cacheHit.toDouble() / allTests * 100.0
        println()
        println("  Smart Flank cache hit: ${cachePercent.roundToInt()}% ($cacheHit / $allTests)")
        println("  Shard times: " + shards.joinToString(", ") { "${it.time.roundToInt()}s" } + "\n")

        return shards
    }

    fun createTestMethodDurationMap(junitResult: JUnitTestResult, args: IArgs): Map<String, Double> {
        val junitMap = mutableMapOf<String, Double>()

        // Create a map with information from previous junit run
        junitResult.testsuites?.forEach { testsuite ->
            testsuite.testcases?.forEach { testcase ->
                if (!testcase.empty() && testcase.time != null) {
                    val key = if (args is AndroidArgs) testcase.androidKey() else testcase.iosKey()
                    val time = testcase.time.toDouble()
                    if (time >= 0) junitMap[key] = time
                }
            }
        }

        return junitMap
    }
}
