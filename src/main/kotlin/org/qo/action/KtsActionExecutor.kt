package org.qo.action

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.constructorArgs
import kotlin.script.experimental.api.valueOrNull
import kotlin.script.experimental.host.FileScriptSource
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate
import org.qo.pipeline.ActionExecutionContext

object OrinActionScriptCompilationConfiguration : ScriptCompilationConfiguration({
	jvm {
		dependenciesFromCurrentContext(wholeClasspath = true)
	}
}) {
	private fun readResolve(): Any = OrinActionScriptCompilationConfiguration
}

@KotlinScript(
	fileExtension = "kts",
	compilationConfiguration = OrinActionScriptCompilationConfiguration::class,
)
abstract class OrinActionScriptTemplate(
	val context: KtsActionContext,
	val orin: KtsActionApi,
)

class KtsActionExecutor {
	private val logger = KotlinLogging.logger("KtsActionExecutor")
	private val host = BasicJvmScriptingHost()
	private val compilationConfiguration: ScriptCompilationConfiguration =
		createJvmCompilationConfigurationFromTemplate<OrinActionScriptTemplate>()

	fun execute(context: ActionExecutionContext, action: LoadedAction) {
		val scriptContext = context.toKtsActionContext(action.manifest.kts.expose)
		val evaluationConfiguration = ScriptEvaluationConfiguration {
			constructorArgs(scriptContext, KtsActionApi(logger, context))
		}
		val result = host.eval(
			script = FileScriptSource(action.entryPath.toFile()),
			compilationConfiguration = compilationConfiguration,
			evaluationConfiguration = evaluationConfiguration,
		)
		result.reports.forEach { report ->
			if (report.message.isNotBlank()) {
				logger.info { "KTS report ${report.severity}: ${report.message}" }
			}
		}
		if (result is ResultWithDiagnostics.Failure) {
			throw IllegalStateException(
				buildString {
					append("Failed to execute kts action ${action.name}.")
					result.reports
						.mapNotNull { it.exception?.message ?: it.message.takeIf(String::isNotBlank) }
						.forEach { message ->
							append(' ')
							append(message)
						}
				}
			)
		}
		val returnValue = result.valueOrNull()?.returnValue
		if (returnValue is ResultValue.Error) {
			throw IllegalStateException("Kts action ${action.name} failed.", returnValue.error)
		}
	}
}
