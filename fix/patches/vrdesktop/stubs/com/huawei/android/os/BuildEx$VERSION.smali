.class public final Lcom/huawei/android/os/BuildEx$VERSION;
.super Ljava/lang/Object;
.source "BuildEx.java"


# annotations
.annotation system Ldalvik/annotation/EnclosingClass;
    value = Lcom/huawei/android/os/BuildEx;
.end annotation

.annotation system Ldalvik/annotation/InnerClass;
    accessFlags = 0x19
    name = "VERSION"
.end annotation


# static fields
.field public static final EMUI_SDK_INT:I


# direct methods
.method static constructor <clinit>()V
    .registers 1

    .line 18
    invoke-static {}, Lcom/huawei/android/os/BuildEx$VERSION;->readEmuiSdkInt()I

    move-result v0

    sput v0, Lcom/huawei/android/os/BuildEx$VERSION;->EMUI_SDK_INT:I

    return-void
.end method

.method private constructor <init>()V
    .registers 1

    .line 15
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    .line 16
    return-void
.end method

.method private static readEmuiSdkInt()I
    .registers 2

    .line 23
    const-string v0, "ro.build.hw_emui_api_level"

    const/4 v1, 0x0

    invoke-static {v0, v1}, Lcom/huawei/android/os/SystemPropertiesEx;->getInt(Ljava/lang/String;I)I

    move-result v0

    return v0
.end method
