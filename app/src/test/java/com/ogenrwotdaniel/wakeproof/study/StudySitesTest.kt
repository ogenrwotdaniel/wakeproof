package com.ogenrwotdaniel.wakeproof.study

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudySitesTest {
    @Test
    fun `blackboard, unist and the sign-in hops open`() {
        assertTrue(studySiteAllowed(BLACKBOARD_URL))
        assertTrue(studySiteAllowed("https://portal.unist.ac.kr/irj/portal"))
        assertTrue(studySiteAllowed("https://unist.ac.kr/"))
        assertTrue(studySiteAllowed("https://eu-lti.bbcollab.com/collab/ui/session/join/x"))
        assertTrue(studySiteAllowed("https://login.microsoftonline.com/common/oauth2"))
        assertTrue(studySiteAllowed("about:blank"))
        assertFalse(studySiteAllowed("data:text/html,<iframe src=https://youtube.com>"))
    }

    @Test
    fun `everything else is handed off`() {
        assertFalse(studySiteAllowed("https://www.youtube.com/watch?v=x"))
        assertFalse(studySiteAllowed("https://evilunist.ac.kr/"))
        assertFalse(studySiteAllowed("https://unist.ac.kr.example.com/"))
        assertFalse(studySiteAllowed("http://blackboard.unist.ac.kr/"))
        assertFalse(studySiteAllowed("https://www.office.com/"))
        assertFalse(studySiteAllowed("https://www.google.com/search?q=unist.ac.kr"))
        assertFalse(studySiteAllowed("intent://x#Intent;end"))
        assertFalse(studySiteAllowed("javascript:alert(1)"))
        assertFalse(studySiteAllowed("not a url"))
    }
}
