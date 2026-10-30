package org.qo

import com.google.gson.Gson
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import org.qo.pipeline.ActionExecutionContext
import org.qo.pipeline.PipelineContext

class GithubRevisionContextTest {
    @Test
    fun `push identity survives parsing and explicit context exposure`() {
        val sha = "a".repeat(40)
        val event = Gson().fromJson("""{"repository":{"name":"QPlugin-Survival2","full_name":"glowingstone124/QPlugin-Survival2"},"commits":[],"sender":{"login":"alice"},"ref":"refs/heads/main","after":"$sha","deleted":false}""", GithubPushEvent::class.java)
        val action = ActionExecutionContext(PipelineContext(Config.Pipeline("test"), "github.push", event), "test", Path.of("/tmp"), Path.of("/tmp"), Path.of("/tmp/main.kts"))
        val github = action.toKtsActionContext(listOf("github")).github!!
        assertEquals("refs/heads/main", github.ref)
        assertEquals(sha, github.after)
        assertEquals("glowingstone124/QPlugin-Survival2", github.fullName)
        assertFalse(github.deleted)
        assertNull(action.toKtsActionContext(emptyList()).github)
    }
}
