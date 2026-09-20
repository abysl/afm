{pkgs, ...}: {
  packages = with pkgs; [
    alsa-lib
    libGL
    libX11
    libXcursor
    libXi
    libXinerama
    libXrandr
    libxkbcommon
    pkg-config
    udev
    wayland
  ];
}
