package com.chardy.doom
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
internal class PixelBreathingView(context:Context):View(context){private val paint=Paint();private var progress=.5f
 fun render(value:Float,reduceMotion:Boolean){progress=if(reduceMotion)BreathingVisuals.staticProgress() else value.coerceIn(0f,1f);invalidate()}
 override fun onDraw(canvas:Canvas){val unit=minOf(width/16f,height/16f).coerceAtLeast(1f);val colors=intArrayOf(BreathingVisuals.ORANGE,0xFF9A3F35.toInt(),BreathingVisuals.GOLD,BreathingVisuals.PAPER);BreathingVisuals.cells(progress).forEach{c->paint.color=colors[c.layer];val l=width/2f+c.x*unit-unit/2;val t=height/2f+c.y*unit-unit/2;canvas.drawRect(l,t,l+(unit-2).coerceAtLeast(1f),t+(unit-2).coerceAtLeast(1f),paint)}}}
