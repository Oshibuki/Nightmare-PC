package com.abrah.nightmare.desktop.canvas

import com.abrah.nightmare.desktop.graph.DesktopNodeTypes
import com.abrah.nightmare.desktop.graph.Node
import com.abrah.nightmare.desktop.graph.NodeType
import com.abrah.nightmare.desktop.graph.Port
import com.abrah.nightmare.desktop.graph.Source
import com.abrah.nightmare.desktop.graph.Widget
import com.abrah.nightmare.desktop.graph.Workflow as Graph

/**
 * Test-only presentation fixtures. The mobile tests leaned on the executor's
 * NODE_TYPES and built-in recipes; the desktop slice has neither by ADR-0007,
 * so these supply just the ports, widths and widget defaults the layout and
 * gesture assertions need. No execution semantics, no NPU/video types.
 */
internal val stateTestTypes: Map<String, NodeType> = listOf(
    NodeType(
        name = "sd15.sample", title = "SD 1.5 sampler",
        outputs = listOf(Port("image", "IMAGE")),
        widgets = DesktopNodeTypes.sdSampler.widgets,
    ),
    NodeType(
        name = "sd15.inpaint", title = "SD 1.5 inpaint",
        inputs = listOf(Port("prompt", "PROMPT"), Port("image", "IMAGE")),
        outputs = listOf(Port("latent", "LATENT")),
        widgets = DesktopNodeTypes.sdSampler.widgets,
    ),
    NodeType(
        name = "sd.vae_decode", title = "Decode",
        inputs = listOf(Port("latent", "LATENT")),
    ),
    NodeType(
        name = "sd.vae_encode", title = "Encode",
        inputs = listOf(Port("image", "IMAGE")),
        outputs = listOf(Port("latent", "LATENT")),
    ),
    NodeType(
        name = "core.output", title = "Output",
        inputs = listOf(Port("media", "IMAGE"), Port("image", "IMAGE")),
        defaultWidth = 300f, showsResult = true,
        widgets = DesktopNodeTypes.output.widgets,
    ),
    NodeType(
        name = "core.image", title = "Picture",
        outputs = listOf(Port("image", "IMAGE")),
        showsResult = true,
        widgets = listOf(Widget("uri", "Picture", "text", "")),
    ),
    NodeType(
        name = "core.prompt", title = "Prompt",
        outputs = listOf(Port("prompt", "PROMPT")),
        defaultWidth = 300f,
        widgets = DesktopNodeTypes.prompt.widgets,
    ),
    NodeType(
        name = "image.crop", title = "Crop",
        inputs = listOf(Port("image", "IMAGE")),
        outputs = listOf(Port("image", "IMAGE")),
    ),
    NodeType(
        name = "image.mask", title = "Mask",
        inputs = listOf(Port("image", "IMAGE")),
    ),
    NodeType(
        name = "image.upscale", title = "Upscale",
        inputs = listOf(Port("image", "IMAGE")),
        outputs = listOf(Port("image", "IMAGE")),
    ),
    NodeType(
        name = "mix", title = "Latent mix",
        inputs = listOf(Port("latent", "LATENT")),
        outputs = listOf(Port("latent", "LATENT")),
    ),
    NodeType(name = "s2", title = "Second sampler", outputs = listOf(Port("latent", "LATENT"))),
).associateBy { it.name }

/** A stand-in for the mobile built-in recipes, enough for the layout assertions. */
internal data class CanvasRecipe(val id: String, val build: () -> Workflow)

private fun workflow(vararg nodes: Node, positions: Map<String, Pt>) = Workflow(
    positions = positions.toMap(),
    graph = Graph(nodes.toList()),
)

internal val RECIPES: List<CanvasRecipe> = listOf(
    CanvasRecipe("txt2img") {
        workflow(
            Node("p", "core.prompt", DesktopNodeTypes.prompt.widgets.associate { it.name to it.default.orEmpty() }),
            Node("s", "sd15.sample", DesktopNodeTypes.sdSampler.widgets.associate { it.name to it.default.orEmpty() },
                inputs = mapOf("prompt" to Source("p"))),
            Node("o", "core.output", inputs = mapOf("image" to Source("s"))),
            positions = mapOf("p" to Pt(0f, 720f), "s" to Pt(420f, 720f), "o" to Pt(840f, 720f)),
        )
    },
    CanvasRecipe("img2img") {
        workflow(
            Node("i", "core.image", mapOf("uri" to "photo.png")),
            Node("s", "sd15.inpaint", inputs = mapOf("image" to Source("i"))),
            Node("o", "core.output", inputs = mapOf("image" to Source("s"))),
            positions = mapOf("i" to Pt(0f, 720f), "s" to Pt(420f, 720f), "o" to Pt(840f, 720f)),
        )
    },
    CanvasRecipe("inpaint") {
        workflow(
            Node("p", "core.prompt", mapOf("prompt" to "a cat")),
            Node("i", "core.image", mapOf("uri" to "photo.png")),
            Node("s", "sd15.inpaint", inputs = mapOf("prompt" to Source("p"), "image" to Source("i"))),
            Node("o", "core.output", inputs = mapOf("image" to Source("s"))),
            positions = mapOf(
                "p" to Pt(0f, 720f), "i" to Pt(0f, 1020f),
                "s" to Pt(420f, 720f), "o" to Pt(840f, 720f),
            ),
        )
    },
)
