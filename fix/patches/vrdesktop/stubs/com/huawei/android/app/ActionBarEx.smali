.class public final Lcom/huawei/android/app/ActionBarEx;
.super Ljava/lang/Object;
.source "ActionBarEx.java"


# direct methods
.method private constructor <init>()V
    .registers 1

    .line 13
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    .line 14
    return-void
.end method

.method public static getDragAnimationStage(Landroid/app/ActionBar;)I
    .registers 1

    .line 20
    const/4 p0, 0x0

    return p0
.end method

.method public static setAppbarBackground(Landroid/app/ActionBar;Landroid/graphics/drawable/Drawable;)V
    .registers 2

    .line 24
    if-nez p0, :cond_3

    return-void

    .line 26
    :cond_3
    :try_start_3
    invoke-virtual {p0, p1}, Landroid/app/ActionBar;->setBackgroundDrawable(Landroid/graphics/drawable/Drawable;)V
    :try_end_6
    .catchall {:try_start_3 .. :try_end_6} :catchall_7

    .line 29
    goto :goto_8

    .line 27
    :catchall_7
    move-exception p0

    .line 30
    :goto_8
    return-void
.end method

.method public static setStartIcon(Landroid/app/ActionBar;ZLandroid/graphics/drawable/Drawable;Landroid/view/View$OnClickListener;)V
    .registers 4

    .line 34
    if-nez p0, :cond_3

    return-void

    .line 36
    :cond_3
    if-nez p1, :cond_a

    .line 37
    const/4 p1, 0x0

    :try_start_6
    invoke-virtual {p0, p1}, Landroid/app/ActionBar;->setDisplayShowHomeEnabled(Z)V

    .line 38
    return-void

    .line 40
    :cond_a
    const/4 p1, 0x1

    invoke-virtual {p0, p1}, Landroid/app/ActionBar;->setDisplayShowHomeEnabled(Z)V

    .line 41
    if-eqz p2, :cond_13

    invoke-virtual {p0, p2}, Landroid/app/ActionBar;->setIcon(Landroid/graphics/drawable/Drawable;)V

    .line 45
    :cond_13
    if-eqz p3, :cond_18

    invoke-virtual {p3}, Ljava/lang/Object;->getClass()Ljava/lang/Class;
    :try_end_18
    .catchall {:try_start_6 .. :try_end_18} :catchall_19

    .line 48
    :cond_18
    goto :goto_1a

    .line 46
    :catchall_19
    move-exception p0

    .line 49
    :goto_1a
    return-void
.end method
