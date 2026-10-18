package com.touristsafety.app.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.touristsafety.app.data.database.AppDatabase
import com.touristsafety.app.data.repository.GeoFenceRepository
import com.touristsafety.app.data.repository.SaveZoneResult
import com.touristsafety.app.domain.model.ZoneType
import com.touristsafety.app.testing.MainDispatcherRule
import com.touristsafety.app.testing.TestGeo
import com.touristsafety.app.testing.awaitUntil
import com.touristsafety.app.testing.inMemoryDatabase
import com.touristsafety.app.testing.testZone
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The drawing rules a user actually hits: you cannot save a shape that is not a polygon, a stray
 * double-tap does not create a zero-length edge, and editing loads what was saved.
 */
@RunWith(AndroidJUnit4::class)
class GeoFenceEditorViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var database: AppDatabase
    private lateinit var repository: GeoFenceRepository

    @Before
    fun setUp() {
        database = inMemoryDatabase()
        repository = GeoFenceRepository(database.geoFenceDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun viewModel(zoneId: Long? = null) = GeoFenceEditorViewModel(repository, zoneId)

    @Test
    fun `a fresh editor starts empty and cannot be saved`() {
        val viewModel = viewModel()

        assertEquals(0, viewModel.state.value.vertexCount)
        assertFalse(viewModel.state.value.canSave)
        assertFalse(viewModel.state.value.validation.isValid)
    }

    @Test
    fun `saving is refused while there are fewer than three vertices`() = runBlocking {
        val viewModel = viewModel()
        viewModel.setName("Half drawn")
        viewModel.addVertex(TestGeo.squareVertices[0])
        viewModel.addVertex(TestGeo.squareVertices[1])

        assertFalse(viewModel.state.value.canSave)
        viewModel.save()

        assertNull(viewModel.savedZoneId.value)
        assertNotNull(viewModel.message.value)
        assertEquals(0, repository.getAllZones().size)
    }

    @Test
    fun `saving is refused when the name is missing`() {
        val viewModel = viewModel()
        TestGeo.squareVertices.forEach(viewModel::addVertex)

        assertFalse(viewModel.state.value.canSave)
        viewModel.save()
        assertNull(viewModel.savedZoneId.value)
    }

    @Test
    fun `undo removes the last vertex and clear empties the ring`() {
        val viewModel = viewModel()
        TestGeo.squareVertices.forEach(viewModel::addVertex)
        assertEquals(4, viewModel.state.value.vertexCount)

        viewModel.undo()
        assertEquals(3, viewModel.state.value.vertexCount)

        viewModel.clearVertices()
        assertEquals(0, viewModel.state.value.vertexCount)
        assertFalse(viewModel.state.value.closed)
    }

    @Test
    fun `a tap on the previous vertex is ignored rather than creating a duplicate`() {
        val viewModel = viewModel()
        val point = TestGeo.squareVertices[0]

        viewModel.addVertex(point)
        viewModel.addVertex(point)

        assertEquals("the same corner must not be added twice", 1, viewModel.state.value.vertexCount)
    }

    @Test
    fun `a refused duplicate tap explains itself instead of silently doing nothing`() {
        val viewModel = viewModel()
        val point = TestGeo.squareVertices[0]
        viewModel.addVertex(point)

        viewModel.addVertex(point)

        // Silently ignoring the tap is what makes the map feel as though it needs two taps per corner.
        assertNotNull(
            "the user must be told why the tap did nothing",
            viewModel.message.value,
        )
    }

    @Test
    fun `a vertex can be added from typed coordinates`() {
        val viewModel = viewModel()

        val added = viewModel.addVertexManually("12.9716", "77.5946")

        assertTrue(added)
        assertEquals(1, viewModel.state.value.vertexCount)
        assertEquals(12.9716, viewModel.state.value.vertices.single().latitude, 0.0)
        assertEquals(77.5946, viewModel.state.value.vertices.single().longitude, 0.0)
    }

    @Test
    fun `typed coordinates accept a comma as the decimal separator`() {
        val viewModel = viewModel()

        // A phone set to a comma-decimal locale produces "12,9716" from its own keyboard.
        assertTrue(viewModel.addVertexManually("12,9716", "77,5946"))

        assertEquals(12.9716, viewModel.state.value.vertices.single().latitude, 0.0000001)
    }

    @Test
    fun `an out-of-range typed coordinate is refused with a reason`() {
        val viewModel = viewModel()

        val added = viewModel.addVertexManually("95.0", "0.0")

        assertFalse(added)
        assertEquals(0, viewModel.state.value.vertexCount)
        assertNotNull(viewModel.message.value)
    }

    @Test
    fun `nonsense typed into the coordinate fields is refused with a reason`() {
        val viewModel = viewModel()

        assertFalse(viewModel.addVertexManually("north", "77.5946"))
        assertFalse(viewModel.addVertexManually("", ""))
        assertFalse(viewModel.addVertexManually("12.9716", "east"))

        assertEquals(0, viewModel.state.value.vertexCount)
        assertNotNull(viewModel.message.value)
    }

    @Test
    fun `a whole polygon can be drawn purely from typed coordinates`() = runBlocking {
        val viewModel = viewModel()
        viewModel.setName("Typed triangle")

        viewModel.addVertexManually("12.9716", "77.5946")
        viewModel.addVertexManually("12.9726", "77.5966")
        viewModel.addVertexManually("12.9706", "77.5966")

        assertEquals(3, viewModel.state.value.vertexCount)
        assertTrue("a typed polygon must be saveable", viewModel.state.value.canSave)

        viewModel.save()
        awaitUntil(message = "the typed polygon was never saved") {
            viewModel.savedZoneId.value != null
        }
        assertEquals(1, repository.getAllZones().size)
    }

    @Test
    fun `closing the ring needs three vertices`() {
        val viewModel = viewModel()
        viewModel.addVertex(TestGeo.squareVertices[0])
        viewModel.addVertex(TestGeo.squareVertices[1])
        viewModel.closeRing()
        assertFalse(viewModel.state.value.closed)

        viewModel.addVertex(TestGeo.squareVertices[2])
        viewModel.closeRing()
        assertTrue(viewModel.state.value.closed)
    }

    @Test
    fun `a valid polygon can be saved and is persisted with its vertices`() = runBlocking {
        val viewModel = viewModel()
        viewModel.setName("Saved zone")
        viewModel.setDescription("A triangle")
        viewModel.setType(ZoneType.DANGEROUS)
        TestGeo.squareVertices.take(3).forEach(viewModel::addVertex)

        assertTrue(viewModel.state.value.canSave)
        viewModel.save()

        awaitUntil(message = "the zone was never saved") { viewModel.savedZoneId.value != null }

        val id = viewModel.savedZoneId.value!!
        val stored = repository.getZone(id)
        assertNotNull(stored)
        assertEquals("Saved zone", stored!!.name)
        assertEquals(ZoneType.DANGEROUS, stored.type)
        assertEquals(3, stored.vertexCount)
        assertEquals(TestGeo.squareVertices.take(3), stored.vertices)
    }

    @Test
    fun `editing loads the existing polygon and preserves its identity`() = runBlocking {
        val created = repository.create(
            testZone(id = 0, type = ZoneType.SAFE, name = "Existing"),
        ) as SaveZoneResult.Saved

        val viewModel = viewModel(created.id)
        awaitUntil(message = "the existing zone was never loaded") {
            viewModel.state.value.name == "Existing"
        }

        assertEquals(4, viewModel.state.value.vertexCount)
        assertEquals(ZoneType.SAFE, viewModel.state.value.type)
        assertFalse(viewModel.state.value.isNew)

        // A round-trip through the editor must not invent a new zone.
        viewModel.setName("Renamed")
        viewModel.save()
        awaitUntil(message = "the update never completed") {
            viewModel.message.value?.contains("saved") == true
        }

        assertEquals(1, repository.getAllZones().size)
        assertEquals("Renamed", repository.getZone(created.id)!!.name)
    }
}
