package com.chardy.doom

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.*

internal class SegmentedBreathProgressView(context:Context):View(context){
 private val track=Paint().apply{color=BreathingVisuals.PANEL}
 private val fill=Paint().apply{color=BreathingVisuals.GOLD}
 private var elapsedMs=0L
 private var durationMs=BREATH_MS
 internal fun render(elapsedMs:Long,durationMs:Long){this.elapsedMs=elapsedMs;this.durationMs=BreathingVisuals.duration(durationMs);invalidate()}
 internal fun fractions():List<Float>{
  val count=kotlin.math.ceil(durationMs.toDouble()/BREATH_MS).toInt()
  return List(count){index->BreathingVisuals.segmentFraction(elapsedMs,durationMs,index)}
 }
 override fun onDraw(canvas:Canvas){
  super.onDraw(canvas)
  val count=kotlin.math.ceil(durationMs.toDouble()/BREATH_MS).toInt()
  val gap=4f*resources.displayMetrics.density
  val segmentWidth=(width-gap*(count-1))/count
  repeat(count){i->val fraction=BreathingVisuals.segmentFraction(elapsedMs,durationMs,i);val left=i*(segmentWidth+gap);canvas.drawRect(left,0f,left+segmentWidth,height.toFloat(),track);canvas.drawRect(left,0f,left+segmentWidth*fraction,height.toFloat(),fill)}
 }
}
internal interface OverlayFrameScheduler { fun post(callback:Runnable); fun remove(callback:Runnable); fun nowNanos():Long }
private class ViewOverlayFrameScheduler(private val view:View):OverlayFrameScheduler{
 override fun post(callback:Runnable){view.postOnAnimation(callback)}
 override fun remove(callback:Runnable){view.removeCallbacks(callback)}
 override fun nowNanos()=SystemClock.elapsedRealtimeNanos()
}
internal data class OverlayRenderSnapshot(val label:String,val bloom:Float,val segments:List<Float>)
internal class EntryGateOverlayUi(val visualRoot:View,val phaseLabel:TextView,val skipToMessages:Button,val leaveInstagram:Button,val debugReport:Button,private val pixel:PixelBreathingView,private val progress:SegmentedBreathProgressView,frameScheduler:OverlayFrameScheduler?=null){
 val root:View get()=visualRoot
 val windowRoots:List<View> = listOf(visualRoot,skipToMessages,leaveInstagram,debugReport)
 private val scheduler=frameScheduler?:ViewOverlayFrameScheduler(root)
 private var closed=false
 private var disposed=false
 private var lastPhase:String?=null
 private var anchorElapsedMs=0L
 private var anchorNanos=0L
 private var durationMs=BREATH_MS
 private var reduceMotion=false
 private val animationFrame=object:Runnable{
  override fun run(){
   if(closed||!root.isAttachedToWindow)return
   val elapsed=BreathingAnimationTimeline.elapsedAt(anchorElapsedMs,anchorNanos,scheduler.nowNanos(),durationMs)
   draw(BreathingVisuals.frame(elapsed,durationMs),reduceMotion,elapsed,durationMs)
   if(!closed&&root.isAttachedToWindow&&elapsed<durationMs)scheduler.post(this)
  }
 }
 fun render(model:EntryGateOverlayModel){
  if(closed)return
  scheduler.remove(animationFrame)
  anchorElapsedMs=model.elapsedMs
  anchorNanos=scheduler.nowNanos()
  durationMs=model.durationMs
  reduceMotion=model.reduceMotion
  draw(model.frame,model.reduceMotion,model.elapsedMs,model.durationMs)
  if(model.elapsedMs<model.durationMs)scheduler.post(animationFrame)
 }
 private fun draw(frame:BreathingFrame,reduced:Boolean,elapsedMs:Long,durationMs:Long){
  if(closed)return
  val presentation=BreathingVisuals.presentation(frame,reduced)
  if(lastPhase!=presentation.label){phaseLabel.text=presentation.label;lastPhase=presentation.label}
  pixel.render(presentation.bloom,false);progress.render(elapsedMs,durationMs)
 }
 internal fun snapshot()=OverlayRenderSnapshot(phaseLabel.text.toString(),pixel.renderedProgress,progress.fractions())
 fun layoutDecoration(left:Int,top:Int,width:Int,height:Int){
  if(closed)return
  val decoration=(visualRoot as FrameLayout).getChildAt(0)
  decoration.layoutParams=FrameLayout.LayoutParams(width,height,Gravity.TOP or Gravity.LEFT).apply{leftMargin=left;topMargin=top}
 }
 fun closeInteraction(){
  if(closed)return
  closed=true
  scheduler.remove(animationFrame)
  windowRoots.forEach{it.isEnabled=false;it.setOnClickListener(null);it.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS}
  pixel.visibility=View.INVISIBLE
 }
 fun dispose(){if(disposed)return;closeInteraction();disposed=true;windowRoots.forEach{it.setOnApplyWindowInsetsListener(null)}}
}
internal object EntryGateOverlayViewFactory{
 fun create(context:Context,onSkipToMessages:()->Unit,onLeaveInstagram:()->Unit,onDebugReport:()->Unit,frameScheduler:OverlayFrameScheduler?=null):EntryGateOverlayUi{
  fun dp(v:Int)=(v*context.resources.displayMetrics.density).toInt()
  val compactLandscape=context.resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE
  val verticalPadding=dp(if(compactLandscape)8 else 20)
  val root=FrameLayout(context).apply{setBackgroundColor(BreathingVisuals.INK);isClickable=false;isFocusable=false}
  val body=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_HORIZONTAL;isClickable=false}
  root.addView(body,FrameLayout.LayoutParams(-1,-1).apply{leftMargin=dp(20);rightMargin=dp(20);topMargin=verticalPadding;bottomMargin=verticalPadding})
  val phase=TextView(context).apply{
   text="Breathe in";textSize=if(compactLandscape)24f else 32f;minHeight=dp(if(compactLandscape)40 else 48)
   setTextColor(BreathingVisuals.PAPER);gravity=Gravity.CENTER;isFocusable=false
   if(Build.VERSION.SDK_INT>=28)isAccessibilityHeading=true
  }
  body.addView(phase,LinearLayout.LayoutParams(-1,-2))
  val pixel=PixelBreathingView(context).apply{importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
  body.addView(pixel,LinearLayout.LayoutParams(-1,0,1f))
  val progress=SegmentedBreathProgressView(context).apply{importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
  body.addView(progress,LinearLayout.LayoutParams(-1,dp(if(compactLandscape)8 else 10)))
  val skip=button(context,"Skip to Messages",BreathingVisuals.INK,BreathingVisuals.GOLD,dp(52)).apply{setOnClickListener{onSkipToMessages()}}
  val leave=button(context,"Leave Instagram",BreathingVisuals.PAPER,BreathingVisuals.PANEL,dp(48)).apply{setOnClickListener{onLeaveInstagram()}}
  val debug=button(context,"Capture debug",BreathingVisuals.GOLD,BreathingVisuals.INK,dp(48)).apply{
   setOnClickListener{onDebugReport()};setBackgroundColor(android.graphics.Color.TRANSPARENT)
  }
  return EntryGateOverlayUi(root,phase,skip,leave,debug,pixel,progress,frameScheduler)
 }
 private fun button(c:Context,label:String,text:Int,fill:Int,height:Int)=Button(c).apply{this.text=label;textSize=16f;minHeight=height;minimumHeight=height;isAllCaps=false;setTextColor(ColorStateList.valueOf(text));background=GradientDrawable().apply{setColor(fill);setStroke(2,BreathingVisuals.GOLD);cornerRadius=4f};stateListAnimator=null}
}
