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

    @Test
    fun `a page is judged on its parsed host, whatever its query holds`() {
        assertTrue(studyPageAllowed("https", "login.microsoftonline.com"))
        assertTrue(studyPageAllowed("https", "Blackboard.UNIST.ac.kr"))
        assertFalse(studyPageAllowed("http", "blackboard.unist.ac.kr"))
        assertFalse(studyPageAllowed("https", null))
        assertFalse(studyPageAllowed("https", "www.youtube.com"))
    }

    @Test
    fun `a university proxy is not a study page`() {
        assertFalse(studySiteAllowed("https://www-youtube-com.libproxy.unist.ac.kr/"))
        assertFalse(studySiteAllowed("https://ezproxy.unist.ac.kr/login?url=https://youtube.com"))
        assertTrue(studySiteAllowed("https://blackboard.unist.ac.kr/ultra/course"))
        // Blackboard's own regional hosts keep their hyphens.
        assertTrue(studySiteAllowed("https://us-lti-prod-01.bbcollab.com/collab/ui"))
    }

    @Test
    fun `a page's parts load only from what blackboard was seen using`() {
        assertTrue(studyPartAllowed("blackboard.unist.ac.kr"))
        assertTrue(studyPartAllowed("prod01-apne2-prod01-xythos.prod.files.blackboard.com"))
        assertTrue(studyPartAllowed("notif-websockets-ap-northeast-2.prod.notifications.api.blackboard.com"))
        assertTrue(studyPartAllowed("dmuwut6e40u5o.cloudfront.net"))
        assertTrue(studyPartAllowed("prod-ap-southeast-2.ally.ac"))
        assertTrue(studyPartAllowed("data.pendo.io"))
        assertTrue(studyPartAllowed("pendo-static-6207542686449664.storage.googleapis.com"))
        assertTrue(studyPartAllowed("learn-ap-southeast-2-prod-fleet01-xythos.content.blackboardcdn.com"))
        assertTrue(studyPartAllowed("logincdn.msftauth.net"))
        // A frame from anywhere else does not load: no YouTube embed, no other CDN tenant.
        assertFalse(studyPartAllowed("www.youtube.com"))
        assertFalse(studyPartAllowed("www.youtube-nocookie.com"))
        assertFalse(studyPartAllowed("rr1---sn-x.googlevideo.com"))
        assertFalse(studyPartAllowed("d1234abcd.cloudfront.net"))
        assertFalse(studyPartAllowed("someone-else.storage.googleapis.com"))
    }
}
