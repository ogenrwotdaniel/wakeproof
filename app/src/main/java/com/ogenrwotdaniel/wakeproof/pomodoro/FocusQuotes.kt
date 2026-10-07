package com.ogenrwotdaniel.wakeproof.pomodoro

/** A line shown under the focus countdown. */
data class FocusQuote(val text: String, val author: String)

/**
 * Quiet lines for the focus screen — offline, like the motivational lines on
 * the alarm screens, and for the same reason: nothing here is worth a network
 * round trip or an API key.
 *
 * Chosen to be short enough to read in one glance and about the work itself,
 * not about hustle. A quote you have to stop and parse is a distraction wearing
 * a cardigan.
 */
object FocusQuotes {
    val all: List<FocusQuote> = listOf(
        FocusQuote("The secret of getting ahead is getting started.", "Mark Twain"),
        FocusQuote("It always seems impossible until it's done.", "Nelson Mandela"),
        FocusQuote("Concentrate all your thoughts upon the work at hand.", "Alexander Graham Bell"),
        FocusQuote("You do not rise to the level of your goals. You fall to the level of your systems.", "James Clear"),
        FocusQuote("What we hope ever to do with ease, we must first learn to do with diligence.", "Samuel Johnson"),
        FocusQuote("Well begun is half done.", "Aristotle"),
        FocusQuote("Do the hard jobs first. The easy jobs will take care of themselves.", "Dale Carnegie"),
        FocusQuote("The expert in anything was once a beginner.", "Helen Hayes"),
        FocusQuote("Little by little, one travels far.", "J.R.R. Tolkien"),
        FocusQuote("Focus is a matter of deciding what things you're not going to do.", "John Carmack"),
        FocusQuote("Education is not the filling of a pail, but the lighting of a fire.", "W.B. Yeats"),
        FocusQuote("I am always doing that which I cannot do, in order that I may learn how to do it.", "Pablo Picasso"),
        FocusQuote("Amateurs sit and wait for inspiration; the rest of us just get up and go to work.", "Stephen King"),
        FocusQuote("The man who moves a mountain begins by carrying away small stones.", "Confucius"),
        FocusQuote("Quality is not an act, it is a habit.", "Will Durant"),
        FocusQuote("Start where you are. Use what you have. Do what you can.", "Arthur Ashe"),
        FocusQuote("Energy and persistence conquer all things.", "Benjamin Franklin"),
        FocusQuote("The beautiful thing about learning is that no one can take it away from you.", "B.B. King"),
        FocusQuote("Don't watch the clock; do what it does. Keep going.", "Sam Levenson"),
        FocusQuote("A river cuts through rock not because of its power, but its persistence.", "Jim Watkins"),
        FocusQuote("Discipline is choosing between what you want now and what you want most.", "Abraham Lincoln (attrib.)"),
        FocusQuote("Action is the foundational key to all success.", "Pablo Picasso"),
        FocusQuote("An investment in knowledge pays the best interest.", "Benjamin Franklin"),
        FocusQuote("Slow and steady wins the race.", "Aesop"),
        FocusQuote("One page at a time.", "WakeProof"),
        FocusQuote("Your phone will still be there in twenty-five minutes.", "WakeProof"),
        FocusQuote("The tree only grows while you stay.", "WakeProof"),
        FocusQuote("Deep work is rare, and therefore valuable.", "Cal Newport"),
        FocusQuote("Where attention goes, energy flows.", "James Redfield"),
        FocusQuote("Study without desire spoils the memory, and it retains nothing that it takes in.", "Leonardo da Vinci")
    )

    /**
     * The quote for one focus block. [epochDay] moves the start point each day
     * and [block] walks forward through the session, so consecutive blocks never
     * repeat and tomorrow's first block is not today's.
     */
    fun forBlock(epochDay: Long, block: Int): FocusQuote =
        all[Math.floorMod(epochDay * 7 + block, all.size.toLong()).toInt()]
}
