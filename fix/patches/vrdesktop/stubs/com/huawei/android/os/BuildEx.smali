.class public final Lcom/huawei/android/os/BuildEx;
.super Ljava/lang/Object;
.source "BuildEx.java"


# annotations
.annotation system Ldalvik/annotation/MemberClasses;
    value = {
        Lcom/huawei/android/os/BuildEx$VERSION;
    }
.end annotation


# direct methods
.method private constructor <init>()V
    .registers 1

    .line 11
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    .line 12
    return-void
.end method

.method public static getRelease()Ljava/lang/String;
    .registers 1

    .line 28
    sget-object v0, Landroid/os/Build$VERSION;->RELEASE:Ljava/lang/String;

    return-object v0
.end method
