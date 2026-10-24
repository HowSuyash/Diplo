package com.dietcode.diplo.exec

import com.dietcode.diplo.exec.RideRecipe.Stage
import com.dietcode.diplo.exec.ScreenElement.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RideRecipeTest {

    private fun screen(vararg items: Pair<Kind, String>) =
        items.mapIndexed { i, (kind, label) -> ScreenElement(i + 1, kind, label) }

    @Test
    fun aRideGoesWhereToThenPlaceThenModeThenBook() {
        val home = screen(Kind.TEXT to "Rapido", Kind.BUTTON to "Where are you going?")
        // The bar opens the search box first.
        assertEquals(RideRecipe.Move(2, null, Stage.TYPE, "दबाया: Where are you going?"), RideRecipe.next(home, "abes college", "bike", Stage.TYPE, 0))

        val search = screen(Kind.INPUT to "Enter destination", Kind.TEXT to "Recent")
        assertEquals(RideRecipe.Move(1, "abes college", Stage.PICK, "लिखा: abes college"), RideRecipe.next(search, "abes college", "bike", Stage.TYPE, 0))

        val results = screen(Kind.INPUT to "abes college", Kind.BUTTON to "ABES Engineering College, Ghaziabad", Kind.BUTTON to "ABESIT")
        assertEquals(2, RideRecipe.next(results, "abes college", "bike", Stage.PICK, 0)!!.n)

        val rides = screen(Kind.BUTTON to "Auto ₹89", Kind.BUTTON to "Bike ₹42", Kind.BUTTON to "Book Bike")
        assertEquals(RideRecipe.Move(2, null, Stage.BOOK, "चुना: Bike ₹42"), RideRecipe.next(rides, "abes college", "bike", Stage.MODE, 0))
        val press = RideRecipe.next(rides, "abes college", "bike", Stage.BOOK, 0)!!
        assertEquals(3, press.n)
        assertEquals(true, press.books)
    }

    @Test
    fun theSecondBookingPressFinishes() {
        val confirm = screen(Kind.BUTTON to "Confirm pickup")
        assertEquals(Stage.DONE, RideRecipe.next(confirm, "cp", null, Stage.BOOK, 1)!!.next)
    }

    @Test
    fun noModeAskedGoesStraightToBooking() {
        val rides = screen(Kind.BUTTON to "Uber Go ₹184", Kind.BUTTON to "Choose Uber Go")
        assertEquals(2, RideRecipe.next(rides, "cp", null, Stage.MODE, 0)!!.n)
    }

    @Test
    fun payButtonsAndUnfamiliarScreensAreLeftToTheUserOrTheModel() {
        assertNull(RideRecipe.next(screen(Kind.BUTTON to "Pay ₹184"), "cp", null, Stage.BOOK, 0))
        assertNull(RideRecipe.next(screen(Kind.TEXT to "Login with OTP"), "cp", null, Stage.TYPE, 0))
    }
}
