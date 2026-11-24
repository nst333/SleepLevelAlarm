# Sleep Level Alarm

<!-- impeccable:product-schema 1 -->

## Platform

android

## Stack

Kotlin, Compose for Wear OS, Room, DataStore. Approved in the implementation plan.

## Users

A Galaxy Watch owner testing a standalone overnight smart alarm, with no phone or network required.

## Product Purpose

Estimate sleep onset and wake suitability from heart rate, IBI and motion. Wake within a chosen deadline window or around a chosen count of nominal 90-minute cycles. Always retain a separately scheduled final alarm.

## Operating Context

The user starts a session at bedtime. Screen-off overnight collection, restrained brightness, large touch targets and clear sensor failures matter. Both deadline and cycle modes, vibration plus optional sound, were selected by the user.

## Capabilities and Constraints

This personal experimental app displays on-device estimates for awake, N1, N2, N3, and REM during tracking. Wake timing uses the alarm policy. Samsung SDK developer mode is for testing. Public distribution needs Samsung approval. Keep summaries 30 days and sensor details seven days locally. Future Android phone sync must not own alarm execution.

## Evidence on Hand

User-approved plan, Samsung Health Sensor SDK 1.4.1, and the [PhysioNet Sleep-Accel dataset](https://physionet.org/content/sleep-accel/1.0.0/). Galaxy Watch sleep stage calibration still requires labeled device recordings. Inspect watch capabilities at runtime.

## Product Principles

- The final alarm does not depend on sensor availability.
- Missing or stale data is visible and never masquerades as sleep.
- Keep the first version small and offline.
- Experimental estimates are explicitly labeled.
