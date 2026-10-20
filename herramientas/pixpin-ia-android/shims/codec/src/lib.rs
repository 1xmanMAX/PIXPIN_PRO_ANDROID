//! **`pixpin-codec` del PC sin Windows**: lo que usa la hoja al lienzo (cargar, PNG, JPEG y
//! redimensionar), con las mismas firmas y el crate `image` en vez de WIC.

pub mod imagen {
    use std::path::Path;

    #[derive(Debug, Clone, PartialEq, Eq)]
    pub struct ImagenRgba {
        pub ancho: u32,
        pub alto: u32,
        pub pixeles: Vec<u8>,
    }

    #[derive(Debug)]
    pub struct ErrorCodec(pub String);

    impl std::fmt::Display for ErrorCodec {
        fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
            f.write_str(&self.0)
        }
    }

    impl std::error::Error for ErrorCodec {}

    pub fn cargar(ruta: &Path) -> Result<ImagenRgba, ErrorCodec> {
        let i = image::open(ruta).map_err(|e| ErrorCodec(e.to_string()))?.to_rgba8();
        Ok(ImagenRgba { ancho: i.width(), alto: i.height(), pixeles: i.into_raw() })
    }

    fn rgba(i: &ImagenRgba) -> Result<image::RgbaImage, ErrorCodec> {
        image::RgbaImage::from_raw(i.ancho, i.alto, i.pixeles.clone()).ok_or_else(|| ErrorCodec("la imagen no cuadra con sus medidas".into()))
    }

    pub fn codificar_png(i: &ImagenRgba) -> Result<Vec<u8>, ErrorCodec> {
        let mut v = std::io::Cursor::new(Vec::new());
        rgba(i)?.write_to(&mut v, image::ImageFormat::Png).map_err(|e| ErrorCodec(e.to_string()))?;
        Ok(v.into_inner())
    }

    pub fn codificar_jpg(i: &ImagenRgba, calidad: u8) -> Result<Vec<u8>, ErrorCodec> {
        let rgb = image::DynamicImage::ImageRgba8(rgba(i)?).to_rgb8();
        let mut v = Vec::new();
        image::codecs::jpeg::JpegEncoder::new_with_quality(&mut v, calidad.clamp(1, 100))
            .encode_image(&rgb)
            .map_err(|e| ErrorCodec(e.to_string()))?;
        Ok(v)
    }

    pub fn redimensionar(i: ImagenRgba, ancho: u32, alto: u32) -> Result<ImagenRgba, ErrorCodec> {
        let r = image::imageops::resize(&rgba(&i)?, ancho.max(1), alto.max(1), image::imageops::FilterType::Triangle);
        Ok(ImagenRgba { ancho: r.width(), alto: r.height(), pixeles: r.into_raw() })
    }
}

pub use imagen::{ErrorCodec, ImagenRgba, cargar, codificar_png, redimensionar};
