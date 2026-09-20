use bevy::prelude::*;
use bevy::window::WindowTheme;

fn main() {
    App::new()
        .insert_resource(ClearColor(Color::rgb(0.08, 0.06, 0.12)))
        .add_plugins(DefaultPlugins.set(WindowPlugin {
            primary_window: Some(Window {
                title: "AFM — Abysl File Manager".into(),
                resolution: (800.0_f32, 450.0_f32).into(),
                window_theme: Some(WindowTheme::Dark),
                ..default()
            }),
            ..default()
        }))
        .add_systems(Startup, hello)
        .run();
}

fn hello() {
    info!("AFM hello world");
}
