package com.example.drowseydriver1

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

private const val ROUTE_SPLASH = "splash"
private const val ROUTE_MAIN = "main"

@Composable
fun AppNav() {
    val nav = rememberNavController()

    NavHost(navController = nav, startDestination = ROUTE_SPLASH) {
        composable(ROUTE_SPLASH) {
            SplashScreen(
                onDone = {
                    nav.navigate(ROUTE_MAIN) {
                        popUpTo(ROUTE_SPLASH) { inclusive = true } // Clear back stack
                    }
                }
            )
        }
        composable(ROUTE_MAIN) {
            MainScreen()
        }
    }
}
