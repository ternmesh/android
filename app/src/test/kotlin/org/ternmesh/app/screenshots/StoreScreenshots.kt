// The store listing's phone screenshots, drawn from the app's own screens on the JVM: Robolectric
// runs them, Roborazzi draws each to a PNG, and the node they show is the one made up below.
//
//     ./gradlew :app:recordRoborazziDebug     writes fastlane/metadata/android/en-US/images/phoneScreenshots/
//     ./gradlew :app:testDebugUnitTest        draws them and writes nothing: CI's check that every screen still does
//
// They are 1080 x 2160, a phone at 9:18, which the Play Store and F-Droid both take. The map is not
// among them: MapLibre draws with OpenGL, which Robolectric has not got.
package org.ternmesh.app.screenshots

import android.Manifest
import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.ternmesh.app.node.ChosenNode
import org.ternmesh.app.node.NodeRepository
import org.ternmesh.app.node.NodeState
import org.ternmesh.app.node.Phase
import org.ternmesh.app.ui.TernApp
import org.ternmesh.app.ui.TernTheme
import org.ternmesh.companion.Address
import org.ternmesh.companion.Body
import org.ternmesh.companion.GroupId
import org.ternmesh.companion.MessageState
import org.ternmesh.companion.Records
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS-w360dp-h720dp-xxhdpi")
class StoreScreenshots {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var repository: NodeRepository

    @Before
    fun setUp() {
        // The times a screen shows are the phone's: the same zone and language on every machine.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        // A repository with no node chosen: nothing here connects, and the screens are given the state below.
        repository = NodeRepository(app)
    }

    private fun show(state: NodeState = Sample.state()) {
        compose.setContent { TernTheme { TernApp(repository, state) } }
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        compose.onRoot().captureRoboImage("$SHOTS/$name.png")
    }

    @Test
    fun chats() {
        show()
        capture("1_chats")
    }

    @Test
    fun conversation() {
        show()
        compose.onNodeWithText("Robin").performClick()
        compose.waitForIdle()
        capture("2_conversation")
    }

    @Test
    fun group() {
        show()
        compose.onNodeWithText("Ridge crew").performClick()
        compose.waitForIdle()
        capture("3_group")
    }

    @Test
    fun contacts() {
        show()
        compose.onNodeWithText("Contacts").performClick()
        compose.waitForIdle()
        capture("4_contacts")
    }

    @Test
    fun whoIsAbout() {
        show()
        compose.onNodeWithText("Contacts").performClick()
        compose.onNodeWithText("Who's about", substring = true).performClick()
        compose.waitForIdle()
        capture("5_who_is_about")
    }

    @Test
    fun node() {
        show()
        compose.onNodeWithText("Node").performClick()
        // Past the address's QR code, to the battery, the airtime, the nodes heard and the settings.
        compose.onNodeWithText("Region").performScrollTo()
        compose.waitForIdle()
        capture("6_node")
    }

    private companion object {
        const val SHOTS = "../fastlane/metadata/android/en-US/images/phoneScreenshots"
    }
}

/** The node the screenshots show. Every name, place and message is made up. */
private object Sample {
    private fun address(seed: Int) = Address(ByteArray(Address.LENGTH) { (seed * 31 + it * 7).toByte() })

    val self = address(1)
    val robin = address(2)
    val sam = address(3)
    val alex = address(4)
    val kit = address(5)
    val crew = address(6)
    val ridge = GroupId(ByteArray(GroupId.LENGTH) { (it * 13 + 5).toByte() })

    /** Today at [hour]:[minute], in seconds: a screen shows a time of today as the time alone. */
    private fun today(hour: Int, minute: Int): Long =
        LocalDate.now(ZoneId.of("UTC")).atTime(hour, minute).atZone(ZoneId.of("UTC")).toEpochSecond()

    private const val READ = 1

    fun state(): NodeState {
        val r = Records()
        listOf(
            Body.Self(self, role = 0, region = "EU868", power = 14, time = today(9, 50), cards = 1, cardName = "Jo"),
            Body.Contact(robin, session = 1, name = "Robin"),
            Body.Contact(sam, session = 1, name = "Sam (the hut)"),
            Body.Contact(alex, session = 0, name = "Alex"),
            Body.Group(ridge, "Ridge crew"),
            Body.Message(1, robin, today(8, 12), READ, MessageState.RECEIVED, 0, 0, "Are you up on the ridge yet?"),
            Body.Message(2, robin, today(8, 14), 0, MessageState.DELIVERED, 0, 0, "Half an hour out. Signal is good from the col."),
            Body.Message(3, robin, today(8, 41), READ, MessageState.RECEIVED, 0, 0, "Kettle is on at the hut."),
            Body.Message(4, robin, today(8, 43), 0, MessageState.DELIVERED, 0, 0, "Perfect. Two of us, maybe three."),
            Body.Message(5, robin, today(8, 44), 0, MessageState.SENT, 0, 0, "Will share my position when we start down."),
            Body.GroupMessage(6, ridge, robin.routingId, today(7, 30), READ, MessageState.RECEIVED, 0, 0, "Morning all. Clear skies above 1800 m."),
            Body.GroupMessage(7, ridge, sam.routingId, today(7, 52), READ, MessageState.RECEIVED, 0, 0, "Hut has water and space for six tonight."),
            Body.GroupMessage(8, ridge, 0, today(8, 5), 0, MessageState.SENT, 0, 0, "Leaving the car park now."),
            Body.GroupMessage(9, ridge, alex.routingId, today(9, 31), 0, MessageState.RECEIVED, 0, 0, "Wind is picking up on the east side."),
            Body.Message(10, sam, today(9, 2), 0, MessageState.RECEIVED, 0, 0, "Saved you two bunks."),
            Body.Position(robin, precision = 18, lat = 461_234_567, lon = 77_654_321, altitude = 2340, accuracy = 40, age = 900),
            Body.Sharing(robin, precision = 18, fields = 3, interval = 600, minutes = 90),
            Body.Card(kit, heard = 1260, name = "Kit · ridge hut"),
            Body.Card(crew, heard = 4080, name = "Trail crew"),
            Body.Neighbour(sam.routingId, role = 1, snrQuarterDb = 38, heard = 14),
            Body.Neighbour(kit.routingId, role = 0, snrQuarterDb = -26, heard = 95),
            Body.Airtime(period = 3600, allowed = 360_000, used = 18_400, wait = 0),
            Body.Power(millivolts = 3987, percent = 81, flags = 0),
        ).forEach(r::apply)
        return NodeState(
            node = ChosenNode("00:11:22:33:44:55", "Tern 5C3A", label = "Jo's node"),
            phase = Phase.READY,
            records = r,
            version = 9,
            firmware = "tern 0.5.0-alpha.1 heltec-v3",
            board = "heltec-v3",
            release = "0.5.0-alpha.1",
            known = listOf(ChosenNode("00:11:22:33:44:55", "Tern 5C3A", label = "Jo's node")),
        )
    }
}
