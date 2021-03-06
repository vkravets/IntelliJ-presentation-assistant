/**
 * @author nik
 */
package org.nik.presentationAssistant

import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.project.Project
import java.awt.Point
import com.intellij.openapi.Disposable
import java.awt.Color
import com.intellij.ui.JBColor
import java.awt.BorderLayout
import com.intellij.ui.components.panels.NonOpaquePanel
import java.awt.Font
import java.awt.FlowLayout
import com.intellij.ui.awt.RelativePoint
import com.intellij.openapi.wm.IdeFrame
import javax.swing.JLabel
import javax.swing.SwingConstants
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.util.Pair as IdeaPair
import com.intellij.ui.popup.ComponentPopupBuilderImpl
import java.awt.geom.RoundRectangle2D
import javax.swing.BorderFactory
import com.intellij.ui.IdeBorderFactory
import com.intellij.util.Alarm
import com.intellij.openapi.util.Disposer
import javax.swing.JPanel
import com.intellij.util.ui.Animator
import com.intellij.util.ui.UIUtil
import com.intellij.openapi.ui.popup.MaskProvider
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import javax.swing.SwingUtilities
import java.util.ArrayList
import java.awt.Window

val hideDelay = 4*1000

fun ComponentPopupBuilderImpl.mySetMaskProvider(provider: MaskProvider) {
    try {
        javaClass<ComponentPopupBuilderImpl>().getMethod("setMaskProvider", javaClass<MaskProvider>()).invoke(this, provider)
    } catch(ignored: NoSuchMethodError) {
    }
}

class ActionInfoPanel(project: Project, textFragments: List<Pair<String, Font?>>) : NonOpaquePanel(BorderLayout()), Disposable {
    private val hint: JBPopup
    private val labelsPanel: JPanel
    private val hideAlarm = Alarm(this);
    private var animator: Animator
    private var phase = Phase.FADING_IN
    private val hintAlpha = if (UIUtil.isUnderDarcula()) 0.05.toFloat() else 0.1.toFloat()
    enum class Phase { FADING_IN; SHOWN; FADING_OUT; HIDDEN}

    {
        val ideFrame = WindowManager.getInstance()!!.getIdeFrame(project)!!
        labelsPanel = NonOpaquePanel(FlowLayout(FlowLayout.CENTER, 0, 0))
        val background = JBColor(Color(186, 238, 186, 120), Color(73, 117, 73))
        updateLabelText(project, textFragments)
        setBackground(background)
        setOpaque(true)
        add(labelsPanel, BorderLayout.CENTER)
        val useRoundedBorder = useRoundedBorder()
        val arcSize = 15
        val emptyBorder = BorderFactory.createEmptyBorder(5, 10, 5, 10)
        if (useRoundedBorder) {
            setBorder(BorderFactory.createCompoundBorder(IdeBorderFactory.createRoundedBorder(arcSize), emptyBorder))
        }
        else {
            setBorder(emptyBorder)
        }

        hint = with (JBPopupFactory.getInstance()!!.createComponentPopupBuilder(this, this) as ComponentPopupBuilderImpl) {
            setAlpha(1.0.toFloat())
            if (useRoundedBorder) {
                mySetMaskProvider(MaskProvider({ RoundRectangle2D.Double(1.0, 1.0, it!!.getWidth()-2, it.getHeight()-2, arcSize.toDouble(), arcSize.toDouble()) }))
            }
            setFocusable(false)
            setBelongsToGlobalPopupStack(false)
            setCancelKeyEnabled(false)
            setCancelCallback { phase = Phase.HIDDEN; true }
            createPopup()
        }
        hint.addListener(object : JBPopupListener {
            override fun beforeShown(lightweightWindowEvent: LightweightWindowEvent?) {}
            override fun onClosed(lightweightWindowEvent: LightweightWindowEvent?) {
                phase = Phase.HIDDEN
            }
        })

        animator = FadeInOutAnimator(true)
        hint.show(computeLocation(ideFrame))
        animator.resume()
    }

    private fun useRoundedBorder(): Boolean {
        try {
            javaClass<ComponentPopupBuilderImpl>().getMethod("setMaskProvider", javaClass<MaskProvider>())
            return true
        }
        catch(ignored: NoSuchMethodException) {
            return false
        }
    }
    
    private fun fadeOut() {
        if (phase != Phase.SHOWN) return
        phase = Phase.FADING_OUT
        Disposer.dispose(animator)
        animator = FadeInOutAnimator(false)
        animator.resume()
    }

    inner class FadeInOutAnimator(val forward: Boolean): Animator("Action Hint Fade In/Out", 5, 100, false, forward) {
        override fun paintNow(frame: Int, totalFrames: Int, cycle: Int) {
            if (forward && phase != Phase.FADING_IN
                || !forward && phase != Phase.FADING_OUT) return
            setAlpha(hintAlpha +(1- hintAlpha)*(totalFrames-frame)/totalFrames)
        }

        override fun paintCycleEnd() {
            if (forward) {
                showFinal()
            }
            else {
                close()
            }
        }
    }

    private fun getHintWindow(): Window? {
        val window = SwingUtilities.windowForComponent(hint.getContent()!!)
        if (window != null && window.isShowing()) return window
        return null;
    }

    private fun setAlpha(alpha: Float) {
        val window = getHintWindow()
        if (window != null) {
            WindowManager.getInstance()!!.setAlphaModeRatio(window, alpha)
        }
    }

    private fun showFinal() {
        phase = Phase.SHOWN
        setAlpha(hintAlpha)
        hideAlarm.cancelAllRequests()
        hideAlarm.addRequest({fadeOut()}, hideDelay)
    }

    public fun updateText(project: Project, textFragments: List<Pair<String, Font?>>) {
        if (getHintWindow() == null) return
        labelsPanel.removeAll()
        updateLabelText(project, textFragments)
        hint.getContent()!!.invalidate()
        val ideFrame = WindowManager.getInstance()!!.getIdeFrame(project)!!
        hint.setLocation(computeLocation(ideFrame).getScreenPoint())
        hint.setSize(getPreferredSize()!!)
        hint.getContent()!!.repaint()
        showFinal()
    }

    private fun computeLocation(ideFrame: IdeFrame): RelativePoint {
        val statusBarHeight = ideFrame.getStatusBar()!!.getComponent()!!.getHeight()
        val visibleRect = ideFrame.getComponent()!!.getVisibleRect()
        val popupSize = getPreferredSize()!!
        val point = Point(visibleRect.x + (visibleRect.width - popupSize.width) / 2, visibleRect.y + visibleRect.height - popupSize.height - statusBarHeight - 5)
        return RelativePoint(ideFrame.getComponent()!!, point)
    }

    private fun updateLabelText(project: Project, textFragments: List<Pair<String, Font?>>) {
        val ideFrame = WindowManager.getInstance()!!.getIdeFrame(project)!!
        for (label in createLabels(textFragments, ideFrame)) {
            labelsPanel.add(label)
        }
    }

    private fun List<Pair<String, Font?>>.mergeFragments() : List<Pair<String, Font?>> {
        var result = ArrayList<Pair<String, Font?>>()
        for (item in this) {
            val last = result.last
            if (last != null && last.second == item.second) {
                result.remove(result.lastIndex)
                result.add(Pair(last.first + item.first, last.second))
            }
            else {
                result.add(item)
            }
        }
        return result
    }

    private fun createLabels(textFragments: List<Pair<String, Font?>>, ideFrame: IdeFrame): List<JLabel> {
        var fontSize = getPresentationAssistant().configuration.fontSize.toFloat()
        val labels = textFragments.mergeFragments().map {
            val label = JLabel("<html>${it.first}</html>", SwingConstants.CENTER)
            if (it.second != null) label.setFont(it.second)
            label
        }
        fun setFontSize(size: Float) {
            for (label in labels) {
                label.setFont(label.getFont()!!.deriveFont(size))
            }
            val maxAscent = labels.map { it.getFontMetrics(it.getFont()!!).getMaxAscent() }.max() ?: 0
            for (label in labels) {
                val ascent = label.getFontMetrics(label.getFont()!!).getMaxAscent()
                if (ascent < maxAscent) {
                    label.setBorder(BorderFactory.createEmptyBorder(maxAscent - ascent, 0, 0, 0))
                }
                else {
                    label.setBorder(null)
                }
            }
        }
        setFontSize(fontSize)
        val frameWidth = ideFrame.getComponent()!!.getWidth()
        if (frameWidth > 100) {
            while (labels.map {it.getPreferredSize()!!.width}.sum() > frameWidth - 10 && fontSize > 12) {
                setFontSize(--fontSize)
            }
        }
        return labels
    }

    public fun close() {
        Disposer.dispose(this)
    }

    public override fun dispose() {
        phase = Phase.HIDDEN
        if (!hint.isDisposed()) {
            hint.cancel()
        }
        Disposer.dispose(animator)
    }

    public fun canBeReused(): Boolean = phase == Phase.FADING_IN || phase == Phase.SHOWN
}
