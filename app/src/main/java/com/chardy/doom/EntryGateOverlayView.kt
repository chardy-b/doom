package com.chardy.doom

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.widget.*

internal class SegmentedBreathProgressView(context:Context):View(context){
 private val track=Paint().apply{color=BreathingVisuals.PANEL}
 private val fill=Paint().apply{color=BreathingVisuals.GOLD}
 private var segments:List<Float> = emptyList()
 internal fun render(values:List<Float>){segments=values.toList();invalidate()}
 override fun onDraw(canvas:Canvas){
  super.onDraw(canvas);if(segments.isEmpty())return
  val gap=4f*resources.displayMetrics.density
  val segmentWidth=(width-gap*(segments.size-1))/segments.size
  segments.forEachIndexed{i,fraction->val left=i*(segmentWidth+gap);canvas.drawRect(left,0f,left+segmentWidth,height.toFloat(),track);canvas.drawRect(left,0f,left+segmentWidth*fraction.coerceIn(0f,1f),height.toFloat(),fill)}
 }
}
internal class EntryGateOverlayUi(val visualRoot:View,val phaseLabel:TextView,val skipToMessages:Button,val leaveInstagram:Button,val debugReport:Button,private val pixel:PixelBreathingView,private val progress:SegmentedBreathProgressView){
 val root:View get()=visualRoot
 val windowRoots:List<View> = listOf(visualRoot,skipToMessages,leaveInstagram,debugReport)
 private var closed=false
 private var disposed=false
 private var lastPhase:String?=null
 fun render(model:EntryGateOverlayModel){
  if(closed)return
  if(lastPhase!=model.frame.label){phaseLabel.text=model.frame.label;lastPhase=model.frame.label}
  pixel.render(model.frame.bloom,model.reduceMotion);progress.render(model.frame.segments)
 }
 fun layoutDecoration(left:Int,top:Int,width:Int,height:Int){
  if(closed)return
  val decoration=(visualRoot as FrameLayout).getChildAt(0)
  decoration.layoutParams=FrameLayout.LayoutParams(width,height,Gravity.TOP or Gravity.LEFT).apply{leftMargin=left;topMargin=top}
 }
 fun closeInteraction(){
  if(closed)return
  closed=true
  windowRoots.forEach{it.isEnabled=false;it.setOnClickListener(null);it.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS}
  pixel.visibility=View.INVISIBLE
 }
 fun dispose(){if(disposed)return;closeInteraction();disposed=true;windowRoots.forEach{it.setOnApplyWindowInsetsListener(null)}}
}
internal object EntryGateOverlayViewFactory{
 fun create(context:Context,onSkipToMessages:()->Unit,onLeaveInstagram:()->Unit,onDebugReport:()->Unit):EntryGateOverlayUi{
  fun dp(v:Int)=(v*context.resources.displayMetrics.density).toInt()
  val root=FrameLayout(context).apply{setBackgroundColor(android.graphics.Color.TRANSPARENT);isClickable=false;isFocusable=false}
  val body=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;isClickable=false}
  // Decoration occupies only the upper central region. The surrounding phone stays visible.
  root.addView(body,FrameLayout.LayoutParams(dp(240),dp(240),Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply{topMargin=dp(48)})
  val phase=TextView(context).apply{text="Breathe in";textSize=24f;setTextColor(BreathingVisuals.PAPER);gravity=Gravity.CENTER;isFocusable=false}
  body.addView(phase,LinearLayout.LayoutParams(-1,-2))
  val pixel=PixelBreathingView(context).apply{importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
  body.addView(pixel,LinearLayout.LayoutParams(-1,0,1f))
  val progress=SegmentedBreathProgressView(context).apply{importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
  body.addView(progress,LinearLayout.LayoutParams(-1,dp(8)))
  val skip=button(context,"Skip to Messages",BreathingVisuals.INK,BreathingVisuals.GOLD,dp(52)).apply{setOnClickListener{onSkipToMessages()}}
  val leave=button(context,"Leave Instagram",BreathingVisuals.PAPER,BreathingVisuals.PANEL,dp(48)).apply{setOnClickListener{onLeaveInstagram()}}
  val debug=button(context,"Capture debug",BreathingVisuals.GOLD,BreathingVisuals.INK,dp(48)).apply{setOnClickListener{onDebugReport()}}
  return EntryGateOverlayUi(root,phase,skip,leave,debug,pixel,progress)
 }
 private fun button(c:Context,label:String,text:Int,fill:Int,height:Int)=Button(c).apply{this.text=label;textSize=16f;minHeight=height;minimumHeight=height;isAllCaps=false;setTextColor(ColorStateList.valueOf(text));background=GradientDrawable().apply{setColor(fill);setStroke(2,BreathingVisuals.GOLD);cornerRadius=4f};stateListAnimator=null}
}
