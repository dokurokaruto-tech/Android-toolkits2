// Standalone harness only; runs @Test methods of the classes given as arguments.
import java.lang.reflect.InvocationTargetException

fun main(args: Array<String>) {
    var ran = 0
    val failures = mutableListOf<String>()

    args.forEach { className ->
        val klass = Class.forName(className)
        val tests = klass.declaredMethods.filter { it.isAnnotationPresent(org.junit.Test::class.java) }
        tests.forEach { method ->
            ran++
            try {
                method.isAccessible = true
                method.invoke(klass.getDeclaredConstructor().newInstance())
            } catch (error: InvocationTargetException) {
                failures += "$className#${method.name}: ${error.targetException}"
            } catch (error: ReflectiveOperationException) {
                failures += "$className#${method.name}: $error"
            }
        }
    }

    failures.forEach(::println)
    println("Ran $ran tests, ${failures.size} failed")
    if (failures.isNotEmpty()) {
        kotlin.system.exitProcess(1)
    }
}
