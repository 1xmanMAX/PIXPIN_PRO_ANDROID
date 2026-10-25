fn main() {
    let c = pixpin_motor2d::figuras_de_serie::catalogo();
    eprintln!("{} figuras, version {}", c.len(), pixpin_motor2d::figuras_de_serie::VERSION);
    print!("{}", pixpin_motor2d::biblioteca::a_json(&c));
}
