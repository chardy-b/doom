package com.chardy.doom

import android.content.Context
import android.content.res.ColorStateList
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
internal class EntryGateOverlayUi(val root:View,val phaseLabel:TextView,val skipToMessages:Button,val leaveInstagram:Button,private val pixel:PixelBreathingView,private val progress:SegmentedBreathProgressView){
 private var disposed=false
 fun render(model:EntryGateOverlayModel){if(disposed)return;phaseLabel.text=model.frame.label;pixel.render(model.frame.bloom,model.reduceMotion);progress.render(model.frame.segments)}
 fun dispose(){if(disposed)return;disposed=true;skipToMessages.setOnClickListener(null);leaveInstagram.setOnClickListener(null);skipToMessages.isEnabled=false;leaveInstagram.isEnabled=false;pixel.visibility=View.INVISIBLE}
}
internal object EntryGateOverlayViewFactory{
 fun create(context:Context,onSkipToMessages:()->Unit,onLeaveInstagram:()->Unit):EntryGateOverlayUi{
  fun dp(v:Int)=(v*context.resources.displayMetrics.density).toInt()
  fun label(value:String,size:Float)=TextView(context).apply{ text=value; textSize=size; setTextColor(BreathingVisuals.PAPER); gravity=Gravity.CENTER }
  val scroll=ScrollView(context).apply {
   setBackgroundColor(BreathingVisuals.INK); isFillViewport=true
   setOnApplyWindowInsetsListener { view,insets ->
    val cutout = if(Build.VERSION.SDK_INT>=28) insets.displayCutout?.let { intArrayOf(it.safeInsetLeft,it.safeInsetTop,it.safeInsetRight,it.safeInsetBottom) } else null
    view.setPadding(maxOf(insets.systemWindowInsetLeft,cutout?.get(0)?:0),maxOf(insets.systemWindowInsetTop,cutout?.get(1)?:0),maxOf(insets.systemWindowInsetRight,cutout?.get(2)?:0),maxOf(insets.systemWindowInsetBottom,cutout?.get(3)?:0)); insets
   }
  }
  val body=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_HORIZONTAL;setPadding(dp(20),dp(20),dp(20),dp(20))};scroll.addView(body,FrameLayout.LayoutParams(-1,-2))
  val phase=label("Breathe in",32f).apply{minHeight=dp(48)};body.addView(phase,LinearLayout.LayoutParams(-1,-2))
  val pixel=PixelBreathingView(context).apply{importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO};body.addView(pixel,LinearLayout.LayoutParams(-1,dp(260)).apply{weight=1f})
  val progress=SegmentedBreathProgressView(context);body.addView(progress,LinearLayout.LayoutParams(-1,dp(10)))
  val skip=button(context,"Skip to Messages",BreathingVisuals.INK,BreathingVisuals.GOLD,dp(52)).apply{setOnClickListener{onSkipToMessages()}}
  val leave=button(context,"Leave Instagram",BreathingVisuals.PAPER,BreathingVisuals.PANEL,dp(48)).apply{setOnClickListener{onLeaveInstagram()}}
  body.addView(skip,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(16)});body.addView(leave,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)})
  return EntryGateOverlayUi(scroll,phase,skip,leave,pixel,progress)
 }
 private fun button(c:Context,label:String,text:Int,fill:Int,height:Int)=Button(c).apply{this.text=label;textSize=16f;minHeight=height;minimumHeight=height;isAllCaps=false;setTextColor(ColorStateList.valueOf(text));background=GradientDrawable().apply{setColor(fill);setStroke(2,BreathingVisuals.GOLD);cornerRadius=4f};stateListAnimator=null}
}
