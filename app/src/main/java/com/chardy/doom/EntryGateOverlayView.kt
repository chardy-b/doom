package com.chardy.doom

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
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
internal class EntryGateOverlayUi(val root:View,val phaseLabel:TextView,val skipToMessages:Button,val leaveInstagram:Button,val debugReport:Button,private val pixel:PixelBreathingView,private val progress:SegmentedBreathProgressView){
 private var disposed=false
 private var lastPhase:String?=null
 fun render(model:EntryGateOverlayModel){
  if(disposed)return
  if(lastPhase!=model.frame.label){phaseLabel.text=model.frame.label;lastPhase=model.frame.label}
  pixel.render(model.frame.bloom,model.reduceMotion);progress.render(model.frame.segments)
 }
 fun dispose(){if(disposed)return;disposed=true;skipToMessages.setOnClickListener(null);leaveInstagram.setOnClickListener(null);debugReport.setOnClickListener(null);skipToMessages.isEnabled=false;leaveInstagram.isEnabled=false;debugReport.isEnabled=false;pixel.visibility=View.INVISIBLE}
}
internal object EntryGateOverlayViewFactory{
 fun create(context:Context,onSkipToMessages:()->Unit,onLeaveInstagram:()->Unit,onDebugReport:()->Unit = {}):EntryGateOverlayUi{
  fun dp(v:Int)=(v*context.resources.displayMetrics.density).toInt()
  val compactLandscape=context.resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE
  val verticalPadding=if(compactLandscape)8 else 20
  fun label(value:String,size:Float)=TextView(context).apply{ text=value; textSize=size; setTextColor(BreathingVisuals.PAPER); gravity=Gravity.CENTER }
  val scroll=ScrollView(context).apply {
   setBackgroundColor(BreathingVisuals.INK); isFillViewport=true
   contentDescription="Instagram diagnostic pause"
   setOnApplyWindowInsetsListener { view,insets ->
    val cutout = if(Build.VERSION.SDK_INT>=28) insets.displayCutout?.let { intArrayOf(it.safeInsetLeft,it.safeInsetTop,it.safeInsetRight,it.safeInsetBottom) } else null
    view.setPadding(maxOf(insets.systemWindowInsetLeft,cutout?.get(0)?:0),maxOf(insets.systemWindowInsetTop,cutout?.get(1)?:0),maxOf(insets.systemWindowInsetRight,cutout?.get(2)?:0),maxOf(insets.systemWindowInsetBottom,cutout?.get(3)?:0)); insets
   }
  }
  val body=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_HORIZONTAL;setPadding(dp(20),dp(verticalPadding),dp(20),dp(verticalPadding))};scroll.addView(body,FrameLayout.LayoutParams(-1,-2))
  val phase=label("Breathe in",if(compactLandscape)24f else 32f).apply{
   minHeight=dp(if(compactLandscape)40 else 48);isFocusable=true
   if(Build.VERSION.SDK_INT>=28)isAccessibilityHeading=true
  };body.addView(phase,LinearLayout.LayoutParams(-1,-2))
  val pixel=PixelBreathingView(context).apply{importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO};body.addView(pixel,LinearLayout.LayoutParams(-1,dp(if(compactLandscape)96 else 260)).apply{weight=1f})
  val progress=SegmentedBreathProgressView(context);body.addView(progress,LinearLayout.LayoutParams(-1,dp(if(compactLandscape)8 else 10)))
  val skip=button(context,"Skip to Messages",BreathingVisuals.INK,BreathingVisuals.GOLD,dp(52)).apply{setOnClickListener{onSkipToMessages()}}
  val leave=button(context,"Leave Instagram",BreathingVisuals.PAPER,BreathingVisuals.PANEL,dp(48)).apply{setOnClickListener{onLeaveInstagram()}}
  val debug=button(context,"Debug report",BreathingVisuals.GOLD,BreathingVisuals.INK,dp(48)).apply{
   setOnClickListener{onDebugReport()}
   background=android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
  }
  body.addView(skip,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(if(compactLandscape)6 else 16)});body.addView(leave,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(if(compactLandscape)4 else 8)})
  body.addView(debug,LinearLayout.LayoutParams(-2,-2).apply{topMargin=dp(2);gravity=Gravity.CENTER_HORIZONTAL})
  return EntryGateOverlayUi(scroll,phase,skip,leave,debug,pixel,progress)
 }
 private fun button(c:Context,label:String,text:Int,fill:Int,height:Int)=Button(c).apply{this.text=label;textSize=16f;minHeight=height;minimumHeight=height;isAllCaps=false;setTextColor(ColorStateList.valueOf(text));background=GradientDrawable().apply{setColor(fill);setStroke(2,BreathingVisuals.GOLD);cornerRadius=4f};stateListAnimator=null}
}
