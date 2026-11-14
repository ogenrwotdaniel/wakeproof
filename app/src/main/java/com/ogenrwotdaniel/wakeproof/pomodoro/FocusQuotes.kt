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
        FocusQuote("Now I will have less distraction.", "Leonhard Euler (on losing his sight, 1766)"),
        FocusQuote("Read Euler, read Euler, he is the master of us all.", "Pierre-Simon Laplace"),
        FocusQuote("Nothing takes place in the world whose meaning is not that of some maximum or minimum.", "Leonhard Euler"),
        FocusQuote("Mathematicians have tried in vain to this day to discover some order in the sequence of prime numbers.", "Leonhard Euler"),
        FocusQuote("Logic is the foundation of the certainty of all the knowledge we acquire.", "Leonhard Euler"),
        FocusQuote("Euler calculated without apparent effort, as men breathe, or as eagles sustain themselves in the wind.", "François Arago"),
        FocusQuote("If I have seen further, it is by standing on the shoulders of giants.", "Isaac Newton"),
        FocusQuote("It is not knowledge, but the act of learning, which grants the greatest enjoyment.", "Carl Friedrich Gauss"),
        FocusQuote("Few, but ripe.", "Carl Friedrich Gauss"),
        FocusQuote("A mathematician is a person who can find analogies between theorems; a better mathematician can see analogies between proofs.", "Stefan Banach"),
        FocusQuote("The only way to learn mathematics is to do mathematics.", "Paul Halmos"),
        FocusQuote("Don't just read it; fight it! Ask your own questions, look for your own examples.", "Paul Halmos"),
        FocusQuote("We must know. We will know.", "David Hilbert"),
        FocusQuote("In mathematics you don't understand things. You just get used to them.", "John von Neumann"),
        FocusQuote("It is by logic that we prove, but by intuition that we discover.", "Henri Poincaré"),
        FocusQuote("Hard problems are solved by computing the first ten examples by hand.", "Euler's Lab"),
        FocusQuote("One page of derivations from memory is worth ten pages of passive reading.", "Euler's Lab"),
        FocusQuote("Your phone will still be there in twenty-five minutes; the theorem waits for your pen.", "Euler's Lab"),
        FocusQuote("The tree only grows while you stay with the problem.", "Euler's Lab"),
        FocusQuote("From buckling columns to graphene lattices (V − E + F = χ) and 3D rotations, Euler wrote the language of mechanics and vision.", "Euler's Lab"),
        FocusQuote("Every Stone–Wales defect in graphene and every SO(3) camera pose obeys a theorem Euler proved by hand.", "Euler's Lab"),
        FocusQuote("A neural network is an Euler step through a loss landscape; derive the gradient before you trust the loss curve.", "Euler's Lab"),
        FocusQuote("Concentrate all your thoughts upon the work at hand.", "Alexander Graham Bell"),
        FocusQuote("You do not rise to the level of your goals. You fall to the level of your systems.", "James Clear"),
        FocusQuote("What we hope ever to do with ease, we must first learn to do with diligence.", "Samuel Johnson"),
        FocusQuote("Well begun is half done.", "Aristotle"),
        FocusQuote("Focus is a matter of deciding what things you're not going to do.", "John Carmack"),
        FocusQuote("Amateurs sit and wait for inspiration; the rest of us just get up and go to work.", "Stephen King"),
        FocusQuote("The man who moves a mountain begins by carrying away small stones.", "Confucius"),
        FocusQuote("Quality is not an act, it is a habit.", "Will Durant"),
        FocusQuote("Energy and persistence conquer all things.", "Benjamin Franklin"),
        FocusQuote("Deep work is rare, and therefore valuable.", "Cal Newport"),
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
