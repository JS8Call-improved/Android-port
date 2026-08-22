package com.js8call.example.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.content.ContextCompat
import com.js8call.example.R
import com.js8call.example.util.AvatarColor
import com.js8call.example.util.NetworkGraph
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The network map: stations as nodes, who-hears-whom as edges, laid out by
 * a small force simulation with our own station pinned at the center.
 *
 * Same idiom as WaterfallView: a plain custom View drawing with Canvas, no
 * dependencies. The graph is a JS8 band, a few dozen nodes at the outside,
 * so simple O(n²) repulsion per frame is nowhere near a problem.
 *
 * Gestures: drag a node to rearrange, drag empty space to pan, pinch to
 * zoom, tap a node to open it (via [onNodeClick]).
 */
class NetworkMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onNodeClick: ((String) -> Unit)? = null

    private class Node(
        val callsign: String,
        var x: Float,
        var y: Float,
        var vx: Float = 0f,
        var vy: Float = 0f,
        val pinned: Boolean = false
    )

    private var nodes = mutableListOf<Node>()
    private var edges: List<NetworkGraph.Edge> = emptyList()
    private var myCallsign: String = ""
    private var newestObservation = 0L
    private var oldestObservation = 0L

    // World-to-screen transform
    private var panX = 0f
    private var panY = 0f
    private var zoom = 1f

    private val density = resources.displayMetrics.density
    private val nodeRadius = 22f * density
    private val myNodeRadius = 26f * density

    // Simulation constants, in world units (which equal screen pixels at
    // zoom 1). Rest length spaces first-ring nodes comfortably apart.
    private val springLength = 170f * density
    private val springK = 0.02f
    private val repulsionK = 90000f * density * density
    private val damping = 0.80f
    private val maxVelocity = 40f * density
    private var settled = false

    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }
    private val initialPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 16f * density
        isFakeBoldText = true
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 11f * density
    }
    private val arrowPath = Path()

    private val edgeColor = themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
    private val labelColor = themeColor(com.google.android.material.R.attr.colorOnSurface)
    private val ringColor = themeColor(androidx.appcompat.R.attr.colorPrimary)

    init {
        labelPaint.color = labelColor
        ringPaint.color = ringColor
    }

    private fun themeColor(attr: Int): Int {
        val tv = TypedValue()
        context.theme.resolveAttribute(attr, tv, true)
        return if (tv.resourceId != 0) ContextCompat.getColor(context, tv.resourceId) else tv.data
    }

    /** Replace the graph, keeping positions of nodes that are still in it. */
    fun setGraph(graph: NetworkGraph.Graph, myCallsign: String) {
        this.myCallsign = myCallsign
        this.edges = graph.edges
        newestObservation = graph.edges.maxOfOrNull { it.lastObservedAt } ?: 0L
        oldestObservation = graph.edges.minOfOrNull { it.lastObservedAt } ?: 0L

        val existing = nodes.associateBy { it.callsign }
        nodes = graph.nodes.map { call ->
            existing[call] ?: seedNode(call)
        }.toMutableList()

        settled = false
        postInvalidateOnAnimation()
    }

    /**
     * A new node starts on a ring around the center at an angle hashed from
     * its callsign, so the same stations land in roughly the same places
     * every time the map opens.
     */
    private fun seedNode(callsign: String): Node {
        if (callsign == myCallsign) return Node(callsign, 0f, 0f, pinned = true)
        val angle = (callsign.hashCode().toUInt().toDouble() % 6283.0) / 1000.0
        val ring = springLength * (1.0 + (callsign.hashCode().toUInt() % 40u).toDouble() / 100.0)
        return Node(
            callsign,
            (cos(angle) * ring).toFloat(),
            (sin(angle) * ring).toFloat()
        )
    }

    private fun stepSimulation() {
        if (settled || nodes.size < 2) return

        // Repulsion between every pair
        for (i in nodes.indices) {
            for (j in i + 1 until nodes.size) {
                val a = nodes[i]
                val b = nodes[j]
                var dx = b.x - a.x
                var dy = b.y - a.y
                var d2 = dx * dx + dy * dy
                if (d2 < 1f) { dx = 1f; dy = 1f; d2 = 2f }
                val force = repulsionK / d2
                val d = sqrt(d2)
                val fx = force * dx / d
                val fy = force * dy / d
                a.vx -= fx; a.vy -= fy
                b.vx += fx; b.vy += fy
            }
        }

        // Springs along edges
        val byCall = nodes.associateBy { it.callsign }
        for (edge in edges) {
            val a = byCall[edge.from] ?: continue
            val b = byCall[edge.to] ?: continue
            val dx = b.x - a.x
            val dy = b.y - a.y
            val d = hypot(dx, dy).coerceAtLeast(1f)
            val force = springK * (d - springLength)
            val fx = force * dx / d
            val fy = force * dy / d
            a.vx += fx; a.vy += fy
            b.vx -= fx; b.vy -= fy
        }

        // Weak gravity keeps disconnected pieces from drifting away
        var kinetic = 0f
        for (node in nodes) {
            if (node.pinned || node === dragged) {
                node.vx = 0f; node.vy = 0f
                continue
            }
            node.vx = (node.vx - node.x * 0.001f) * damping
            node.vy = (node.vy - node.y * 0.001f) * damping
            val v = hypot(node.vx, node.vy)
            if (v > maxVelocity) {
                node.vx = node.vx / v * maxVelocity
                node.vy = node.vy / v * maxVelocity
            }
            node.x += node.vx
            node.y += node.vy
            kinetic += node.vx * node.vx + node.vy * node.vy
        }

        if (kinetic < 0.05f * density * density) settled = true
    }

    override fun onDraw(canvas: Canvas) {
        stepSimulation()

        canvas.save()
        canvas.translate(width / 2f + panX, height / 2f + panY)
        canvas.scale(zoom, zoom)

        val byCall = nodes.associateBy { it.callsign }
        val drawnPairs = HashSet<Long>()

        for (edge in edges) {
            val a = byCall[edge.from] ?: continue
            val b = byCall[edge.to] ?: continue

            val strength = NetworkGraph.strength(edge.snr)
            edgePaint.color = edgeColor
            edgePaint.alpha = (80 + 160 * strength).toInt()
            edgePaint.strokeWidth = (1.5f + 4.5f * strength) * density

            // One line per pair; a second direction adds only its arrowhead.
            val key = pairKey(edge.from, edge.to)
            if (drawnPairs.add(key)) {
                canvas.drawLine(a.x, a.y, b.x, b.y, edgePaint)
            }

            // Arrowhead near the listener: edge.from is the station that
            // hears, so the arrow points into it.
            drawArrowhead(canvas, fromX = b.x, fromY = b.y, toX = a.x, toY = a.y,
                nodeRadius = radiusOf(edge.from), alpha = edgePaint.alpha)
        }

        for (node in nodes) {
            val r = radiusOf(node.callsign)
            nodePaint.color = ContextCompat.getColor(
                context, AvatarColor.forCallsign(node.callsign)
            )
            canvas.drawCircle(node.x, node.y, r, nodePaint)
            if (node.callsign == myCallsign) {
                canvas.drawCircle(node.x, node.y, r + 4f * density, ringPaint)
            }
            canvas.drawText(
                node.callsign.take(1),
                node.x,
                node.y - (initialPaint.ascent() + initialPaint.descent()) / 2f,
                initialPaint
            )
            // drawText's y is the baseline, so clear the circle (and the
            // ring on our own node) by the text's ascent plus a real gap.
            val ringExtra = if (node.callsign == myCallsign) 6f * density else 0f
            canvas.drawText(
                node.callsign,
                node.x,
                node.y + r + ringExtra + 10f * density - labelPaint.ascent(),
                labelPaint
            )
        }

        canvas.restore()

        if (!settled) postInvalidateOnAnimation()
    }

    private fun radiusOf(callsign: String) =
        if (callsign == myCallsign) myNodeRadius else nodeRadius

    private fun pairKey(a: String, b: String): Long {
        val (lo, hi) = if (a < b) a to b else b to a
        return lo.hashCode().toLong() shl 32 xor (hi.hashCode().toLong() and 0xFFFFFFFFL)
    }

    private fun drawArrowhead(
        canvas: Canvas, fromX: Float, fromY: Float, toX: Float, toY: Float,
        nodeRadius: Float, alpha: Int
    ) {
        val angle = atan2((toY - fromY).toDouble(), (toX - fromX).toDouble())
        // Tip sits just outside the node circle
        val tipX = toX - (nodeRadius + 6f * density) * cos(angle).toFloat()
        val tipY = toY - (nodeRadius + 6f * density) * sin(angle).toFloat()
        val size = 9f * density
        val back = angle + Math.PI
        val spread = 0.45
        arrowPath.reset()
        arrowPath.moveTo(tipX, tipY)
        arrowPath.lineTo(
            tipX + (size * cos(back - spread)).toFloat(),
            tipY + (size * sin(back - spread)).toFloat()
        )
        arrowPath.lineTo(
            tipX + (size * cos(back + spread)).toFloat(),
            tipY + (size * sin(back + spread)).toFloat()
        )
        arrowPath.close()
        arrowPaint.color = edgeColor
        arrowPaint.alpha = alpha
        canvas.drawPath(arrowPath, arrowPaint)
    }

    // ------------------------------------------------------------------
    // Gestures

    private var dragged: Node? = null
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var downX = 0f
    private var downY = 0f
    private var moved = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoom = (zoom * detector.scaleFactor).coerceIn(0.3f, 3f)
                invalidate()
                return true
            }
        }
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                downX = event.x
                downY = event.y
                moved = false
                dragged = hitTest(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> {
                if (scaleDetector.isInProgress) return true
                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY
                if (hypot(event.x - downX, event.y - downY) > 8f * density) moved = true
                val node = dragged
                if (node != null) {
                    if (!node.pinned) {
                        node.x += dx / zoom
                        node.y += dy / zoom
                        settled = false
                    }
                } else {
                    panX += dx
                    panY += dy
                }
                lastTouchX = event.x
                lastTouchY = event.y
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val node = dragged
                dragged = null
                if (node != null && !moved) {
                    onNodeClick?.invoke(node.callsign)
                } else if (node != null) {
                    node.vx = 0f; node.vy = 0f
                    settled = false
                    postInvalidateOnAnimation()
                }
            }
            MotionEvent.ACTION_CANCEL -> dragged = null
        }
        return true
    }

    private fun hitTest(screenX: Float, screenY: Float): Node? {
        val worldX = (screenX - width / 2f - panX) / zoom
        val worldY = (screenY - height / 2f - panY) / zoom
        return nodes.lastOrNull {
            hypot(it.x - worldX, it.y - worldY) <= radiusOf(it.callsign) + 10f * density
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Start zoomed to fit a first ring comfortably on small screens
        if (oldw == 0 && w > 0) {
            zoom = min(1f, w / (springLength * 3.2f)).coerceAtLeast(0.5f)
        }
    }
}
